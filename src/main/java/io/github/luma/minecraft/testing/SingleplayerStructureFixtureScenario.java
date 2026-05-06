package io.github.luma.minecraft.testing;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Exercises saved redstone structure fixtures through normal player interaction and live undo.
 */
final class SingleplayerStructureFixtureScenario {

    private static final int SETTLE_TICKS = 40;
    private static final List<FixtureSpec> FIXTURES = List.of(
            new FixtureSpec("bud", Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "testing/bud")),
            new FixtureSpec("door", Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "testing/door")),
            new FixtureSpec("main", Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "testing/main"))
    );

    private final ServerLevel level;
    private final ServerPlayer player;
    private final SingleplayerTestVolume volume;
    private final String projectName;
    private final String projectId;
    private final UndoRedoService undoRedoService = new UndoRedoService();
    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final SingleplayerPlayerActionDriver playerActions;
    private final List<StructureFixtureCheck> checks = new ArrayList<>();

    private Stage stage = Stage.PREPARE_FIXTURE;
    private int fixtureIndex;
    private int controlIndex;
    private int waitTicks;
    private FixtureSpec currentFixture;
    private List<StructureFixtureControl> controls = List.of();
    private StructureFixtureControl currentControl;
    private StructureFixtureSnapshot baseline;

    SingleplayerStructureFixtureScenario(
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            String projectName,
            String projectId
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.player = Objects.requireNonNull(player, "player");
        this.volume = Objects.requireNonNull(volume, "volume");
        this.projectName = Objects.requireNonNull(projectName, "projectName");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.playerActions = new SingleplayerPlayerActionDriver(level, player);
    }

    StructureFixtureStepResult advance(MinecraftServer server) {
        List<String> messages = new ArrayList<>();
        while (true) {
            switch (this.stage) {
                case PREPARE_FIXTURE -> this.prepareFixture(server, messages);
                case LOAD_CONTROL_CASE -> this.loadControlCase(server, messages);
                case PRESS_CONTROL -> this.pressControl(messages);
                case WAIT_AFTER_CONTROL -> {
                    this.waitTicks += 1;
                    if (this.waitTicks < SETTLE_TICKS) {
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.stage = Stage.CHECK_CHANGED;
                }
                case CHECK_CHANGED -> {
                    OperationHandle handle = this.checkChangedAndQueueUndo(messages);
                    if (handle != null) {
                        return StructureFixtureStepResult.operation(messages, handle);
                    }
                }
                case VERIFY_UNDO -> this.verifyUndo(messages);
                case FINISHED -> {
                    return StructureFixtureStepResult.finished(messages);
                }
            }
        }
    }

    List<StructureFixtureCheck> checks() {
        return List.copyOf(this.checks);
    }

    void cleanup() {
        this.clearVolume();
    }

    private void prepareFixture(MinecraftServer server, List<String> messages) {
        if (this.fixtureIndex >= FIXTURES.size()) {
            this.stage = Stage.FINISHED;
            return;
        }

        this.currentFixture = FIXTURES.get(this.fixtureIndex);
        StructureTemplate template = this.template(server, this.currentFixture);
        if (template == null) {
            this.record(false, this.currentFixture.name() + " fixture resource is available");
            this.fixtureIndex += 1;
            return;
        }
        if (!this.fits(template)) {
            this.record(false, this.currentFixture.name() + " fixture fits in the reserved test volume");
            this.fixtureIndex += 1;
            return;
        }

        this.loadTemplate(template);
        this.controls = StructureFixtureControl.findAll(this.level, this.volume);
        this.record(!this.controls.isEmpty(),
                this.currentFixture.name() + " fixture exposes button or lever controls");
        messages.add("Lumi structure fixture " + this.currentFixture.name()
                + " has " + this.controls.size() + " control(s)");
        if (this.controls.isEmpty()) {
            this.fixtureIndex += 1;
            return;
        }

        this.controlIndex = 0;
        this.stage = Stage.LOAD_CONTROL_CASE;
    }

    private void loadControlCase(MinecraftServer server, List<String> messages) {
        if (this.controlIndex >= this.controls.size()) {
            this.fixtureIndex += 1;
            this.stage = Stage.PREPARE_FIXTURE;
            return;
        }

        StructureTemplate template = this.template(server, this.currentFixture);
        if (template == null) {
            this.record(false, this.currentFixture.name() + " fixture can be reloaded for control "
                    + (this.controlIndex + 1));
            this.fixtureIndex += 1;
            this.stage = Stage.PREPARE_FIXTURE;
            return;
        }

        this.loadTemplate(template);
        this.historyManager.clearProject(this.projectId);
        this.currentControl = this.controls.get(this.controlIndex).at(this.volume.min());
        this.baseline = StructureFixtureSnapshot.capture(this.level, this.volume);
        messages.add("Lumi structure fixture " + this.currentFixture.name()
                + " control " + (this.controlIndex + 1) + "/" + this.controls.size()
                + " at " + this.format(this.currentControl.pos()));
        this.stage = Stage.PRESS_CONTROL;
    }

    private void pressControl(List<String> messages) {
        boolean baselineLoaded = this.baseline.matches(StructureFixtureSnapshot.capture(this.level, this.volume));
        this.record(baselineLoaded, this.currentFixture.name() + " "
                + this.currentControl.label() + " starts from the saved fixture state");

        boolean used = this.playerActions.useBlock(this.currentControl.pos(), this.currentControl.face());
        this.record(used, this.currentFixture.name() + " "
                + this.currentControl.label() + " was pressed through player interaction");
        messages.add("Pressed " + this.currentFixture.name() + " " + this.currentControl.label()
                + "; waiting " + SETTLE_TICKS + " ticks");
        this.waitTicks = 0;
        this.stage = Stage.WAIT_AFTER_CONTROL;
    }

    private OperationHandle checkChangedAndQueueUndo(List<String> messages) {
        StructureFixtureSnapshot changed = StructureFixtureSnapshot.capture(this.level, this.volume);
        boolean changedAfterPress = !this.baseline.matches(changed);
        this.record(changedAfterPress, this.currentFixture.name() + " "
                + this.currentControl.label() + " changed blocks or entities after "
                + SETTLE_TICKS + " ticks");
        if (!changedAfterPress) {
            this.controlIndex += 1;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return null;
        }

        try {
            OperationHandle handle = this.undoRedoService.undo(this.level, this.projectName);
            messages.add("Queued Alt+Z-equivalent Lumi undo for "
                    + this.currentFixture.name() + " " + this.currentControl.label());
            this.stage = Stage.VERIFY_UNDO;
            return handle;
        } catch (Exception exception) {
            this.record(false, this.currentFixture.name() + " " + this.currentControl.label()
                    + " queued undo: " + this.errorMessage(exception));
            this.controlIndex += 1;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return null;
        }
    }

    private void verifyUndo(List<String> messages) {
        StructureFixtureSnapshot restored = StructureFixtureSnapshot.capture(this.level, this.volume);
        boolean matches = this.baseline.matches(restored);
        this.record(matches, this.currentFixture.name() + " " + this.currentControl.label()
                + " returned exactly to the saved fixture after undo"
                + (matches ? "" : ": " + this.baseline.diff(restored)));
        messages.add("Verified undo for " + this.currentFixture.name() + " " + this.currentControl.label());
        this.controlIndex += 1;
        this.stage = Stage.LOAD_CONTROL_CASE;
    }

    private StructureTemplate template(MinecraftServer server, FixtureSpec fixture) {
        return server.getStructureManager().get(fixture.id()).orElse(null);
    }

    private boolean fits(StructureTemplate template) {
        Vec3i size = template.getSize();
        return size.getX() <= SingleplayerTestVolume.WIDTH
                && size.getY() <= SingleplayerTestVolume.HEIGHT
                && size.getZ() <= SingleplayerTestVolume.DEPTH;
    }

    private void loadTemplate(StructureTemplate template) {
        this.clearVolume();
        try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression();
             WorldMutationContext.SourceFrame source = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setIgnoreEntities(false)
                    .setFinalizeEntities(true);
            template.placeInWorld(
                    this.level,
                    this.volume.min(),
                    this.volume.min(),
                    settings,
                    RandomSource.create(1L),
                    3);
        }
    }

    private void clearVolume() {
        try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression();
             WorldMutationContext.SourceFrame source = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
            for (Entity entity : this.level.getEntities((Entity) null, this.volume.bounds(),
                    entity -> !(entity instanceof ServerPlayer))) {
                entity.discard();
            }
            for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private void record(boolean passed, String label) {
        this.checks.add(new StructureFixtureCheck(label, passed));
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String errorMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record FixtureSpec(String name, Identifier id) {
    }

    record StructureFixtureCheck(String label, boolean passed) {
    }

    private enum Stage {
        PREPARE_FIXTURE,
        LOAD_CONTROL_CASE,
        PRESS_CONTROL,
        WAIT_AFTER_CONTROL,
        CHECK_CHANGED,
        VERIFY_UNDO,
        FINISHED
    }
}
