package io.github.luma.minecraft.testing;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Exercises saved redstone structure fixtures through normal player interaction and live undo.
 */
final class SingleplayerStructureFixtureScenario {

    private static final int SETTLE_TICKS = 40;
    private static final int FIXTURE_LOAD_FLAGS = 2;
    private static final FixtureSpec GENERATED_OBSERVER_PISTON_FIXTURE =
            new FixtureSpec("observer-piston", null);
    private static final FixtureSpec GENERATED_CLOSED_OBSERVER_PISTON_FIXTURE =
            new FixtureSpec("closed-observer-piston", null);
    private static final List<FixtureSpec> FIXTURES = List.of(
            GENERATED_OBSERVER_PISTON_FIXTURE,
            GENERATED_CLOSED_OBSERVER_PISTON_FIXTURE,
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
    private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
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
    private UndoRedoAction queuedUndoAction;

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
                case WAIT_AFTER_LOAD -> {
                    this.waitTicks += 1;
                    if (this.waitTicks < SETTLE_TICKS) {
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.captureLoadedBaseline(server, messages);
                }
                case PRESS_CONTROL -> this.pressControl(messages);
                case WAIT_AFTER_CONTROL -> {
                    this.waitTicks += 1;
                    if (this.waitTicks < SETTLE_TICKS) {
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.stage = Stage.CHECK_CHANGED;
                }
                case CHECK_CHANGED -> {
                    OperationHandle handle = this.checkChangedAndQueueUndo(server, messages);
                    if (handle != null) {
                        return StructureFixtureStepResult.operation(messages, handle);
                    }
                }
                case WAIT_AFTER_UNDO -> {
                    this.waitTicks += 1;
                    if (this.waitTicks < SETTLE_TICKS) {
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.stage = Stage.VERIFY_UNDO;
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
        if (this.isGeneratedFixture(this.currentFixture)) {
            this.loadGeneratedFixture(this.currentFixture);
            this.controls = List.of(this.generatedObserverPistonControl());
            this.record(true, this.currentFixture.name() + " generated observer piston fixture is available");
            messages.add("Lumi structure fixture " + this.currentFixture.name()
                    + " has " + this.controls.size() + " generated control(s)");
            this.controlIndex = 0;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return;
        }

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

        if (this.isGeneratedFixture(this.currentFixture)) {
            if (!this.resetLiveHistory(server)) {
                this.record(false, this.currentFixture.name() + " fixture reset live history for control "
                        + (this.controlIndex + 1));
                this.controlIndex += 1;
                this.stage = Stage.LOAD_CONTROL_CASE;
                return;
            }
            this.loadGeneratedFixture(this.currentFixture);
            this.currentControl = this.controls.get(this.controlIndex).at(this.volume.min());
            this.queuedUndoAction = null;
            messages.add("Lumi structure fixture " + this.currentFixture.name()
                    + " control " + (this.controlIndex + 1) + "/" + this.controls.size()
                    + " at " + this.format(this.currentControl.pos())
                    + "; settling generated fixture for " + SETTLE_TICKS + " ticks");
            this.waitTicks = 0;
            this.stage = Stage.WAIT_AFTER_LOAD;
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

        if (!this.resetLiveHistory(server)) {
            this.record(false, this.currentFixture.name() + " fixture reset live history for control "
                    + (this.controlIndex + 1));
            this.controlIndex += 1;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return;
        }
        this.loadTemplate(template);
        this.currentControl = this.controls.get(this.controlIndex).at(this.volume.min());
        this.queuedUndoAction = null;
        messages.add("Lumi structure fixture " + this.currentFixture.name()
                + " control " + (this.controlIndex + 1) + "/" + this.controls.size()
                + " at " + this.format(this.currentControl.pos())
                + "; settling loaded fixture for " + SETTLE_TICKS + " ticks");
        this.waitTicks = 0;
        this.stage = Stage.WAIT_AFTER_LOAD;
    }

    private void captureLoadedBaseline(MinecraftServer server, List<String> messages) {
        if (!this.resetLiveHistory(server)) {
            this.record(false, this.currentFixture.name() + " fixture reset settled history for control "
                    + (this.controlIndex + 1));
            this.controlIndex += 1;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return;
        }
        this.baseline = StructureFixtureSnapshot.capture(this.level, this.volume);
        messages.add("Captured settled baseline for " + this.currentFixture.name()
                + " " + this.currentControl.label());
        this.stage = Stage.PRESS_CONTROL;
    }

    private void pressControl(List<String> messages) {
        boolean baselineLoaded = this.baseline.matches(StructureFixtureSnapshot.capture(this.level, this.volume));
        this.record(baselineLoaded, this.currentFixture.name() + " "
                + this.currentControl.label() + " starts from the settled loaded fixture state");

        boolean used = this.playerActions.useBlock(this.currentControl.pos(), this.currentControl.face());
        this.record(used, this.currentFixture.name() + " "
                + this.currentControl.label() + " was pressed through player interaction");
        messages.add("Pressed " + this.currentFixture.name() + " " + this.currentControl.label()
                + "; waiting " + SETTLE_TICKS + " ticks");
        this.waitTicks = 0;
        this.stage = Stage.WAIT_AFTER_CONTROL;
    }

    private OperationHandle checkChangedAndQueueUndo(MinecraftServer server, List<String> messages) {
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
            this.captureManager.drainUndoRedoStabilization(server, this.projectId);
            this.queuedUndoAction = this.historyManager.recentUndoActions(this.projectId, 1)
                    .stream()
                    .findFirst()
                    .orElse(null);
            OperationHandle handle = this.undoRedoService.undo(this.level, this.projectName);
            messages.add("Queued Alt+Z-equivalent Lumi undo for "
                    + this.currentFixture.name() + " " + this.currentControl.label()
                    + "; waiting " + SETTLE_TICKS + " ticks after undo");
            this.waitTicks = 0;
            this.stage = Stage.WAIT_AFTER_UNDO;
            return handle;
        } catch (Exception exception) {
            this.record(false, this.currentFixture.name() + " " + this.currentControl.label()
                    + " queued undo: " + this.errorMessage(exception));
            this.controlIndex += 1;
            this.stage = Stage.LOAD_CONTROL_CASE;
            return null;
        }
    }

    private boolean resetLiveHistory(MinecraftServer server) {
        try {
            this.captureManager.discardSession(server, this.projectId);
            this.historyManager.clearProject(this.projectId);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private void verifyUndo(List<String> messages) {
        StructureFixtureSnapshot restored = StructureFixtureSnapshot.capture(this.level, this.volume);
        StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy = this.undoComparisonPolicy();
        boolean matches = this.baseline.matches(restored, comparisonPolicy);
        this.record(matches, this.currentFixture.name() + " " + this.currentControl.label()
                + " returned exactly to the settled loaded fixture after undo"
                + (matches ? "" : ": " + this.baseline.diff(restored, comparisonPolicy)
                + "; " + this.describeQueuedUndoForMismatch(restored, comparisonPolicy)));
        if (this.isGeneratedObserverPistonFixture(this.currentFixture)) {
            this.verifyGeneratedObserverPistonSmoke();
        }
        if (this.isGeneratedClosedObserverPistonFixture(this.currentFixture)) {
            this.verifyGeneratedClosedObserverPistonSmoke();
        }
        messages.add("Verified undo for " + this.currentFixture.name() + " " + this.currentControl.label());
        this.controlIndex += 1;
        this.stage = Stage.LOAD_CONTROL_CASE;
    }

    private StructureFixtureSnapshot.ComparisonPolicy undoComparisonPolicy() {
        if (!this.isGeneratedClosedObserverPistonFixture(this.currentFixture)) {
            return StructureFixtureSnapshot.exactComparison();
        }
        return StructureFixtureSnapshot.ignoringObserverPoweredAt(List.of(
                this.generatedClosedObserverPistonObserverHomePos(),
                this.generatedClosedObserverPistonPairedObserverPos()
        ));
    }

    private String describeQueuedUndoForMismatch(
            StructureFixtureSnapshot restored,
            StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy
    ) {
        StructureFixtureSnapshot.BlockMismatch mismatch = this.baseline.firstBlockMismatch(restored, comparisonPolicy);
        if (mismatch == null) {
            return this.queuedUndoAction == null
                    ? "undoAction=<missing>"
                    : "undoAction=" + this.queuedUndoAction.id()
                    + " blocks=" + this.queuedUndoAction.undoChanges().size()
                    + " mismatch=entities";
        }
        if (this.queuedUndoAction == null) {
            return "undoAction=<missing> mismatchRel=" + this.formatRelative(mismatch.pos());
        }

        StoredBlockChange actionChange = this.queuedUndoAction.undoChanges()
                .stream()
                .filter(change -> this.samePos(change, mismatch.pos()))
                .findFirst()
                .orElse(null);
        if (actionChange == null) {
            return "undoAction=" + this.queuedUndoAction.id()
                    + " blocks=" + this.queuedUndoAction.undoChanges().size()
                    + " mismatchRel=" + this.formatRelative(mismatch.pos())
                    + " actionContains=false";
        }

        return "undoAction=" + this.queuedUndoAction.id()
                + " blocks=" + this.queuedUndoAction.undoChanges().size()
                + " mismatchRel=" + this.formatRelative(mismatch.pos())
                + " actionContains=true"
                + " undoTarget=" + this.truncated(actionChange.oldValue().toStateSnbt())
                + " redoTarget=" + this.truncated(actionChange.newValue().toStateSnbt());
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
                    FIXTURE_LOAD_FLAGS);
        }
    }

    private void loadGeneratedObserverPistonFixture() {
        this.clearVolume();
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            BlockPos support = this.generatedObserverPistonLeverPos().below();
            BlockPos lever = this.generatedObserverPistonLeverPos();
            BlockPos piston = this.generatedObserverPistonBasePos();
            BlockPos observer = this.generatedObserverPistonObserverHomePos();

            this.level.setBlock(support, Blocks.STONE.defaultBlockState(), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(lever, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                    .setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, false), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.EAST)
                    .setValue(PistonBaseBlock.EXTENDED, false), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(observer, Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Direction.EAST), FIXTURE_LOAD_FLAGS);
        });
    }

    private void loadGeneratedClosedObserverPistonFixture() {
        this.clearVolume();
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            BlockPos support = this.generatedObserverPistonLeverPos().below();
            BlockPos lever = this.generatedObserverPistonLeverPos();
            BlockPos piston = this.generatedObserverPistonBasePos();
            BlockPos observer = this.generatedClosedObserverPistonObserverHomePos();
            BlockPos pairedObserver = this.generatedClosedObserverPistonPairedObserverPos();

            this.level.setBlock(support, Blocks.STONE.defaultBlockState(), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(lever, Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                    .setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, false), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP)
                    .setValue(PistonBaseBlock.EXTENDED, false), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(observer, Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Direction.EAST), FIXTURE_LOAD_FLAGS);
            this.level.setBlock(pairedObserver, Blocks.OBSERVER.defaultBlockState()
                    .setValue(ObserverBlock.FACING, Direction.WEST), FIXTURE_LOAD_FLAGS);
        });
    }

    private void loadGeneratedFixture(FixtureSpec fixture) {
        if (this.isGeneratedClosedObserverPistonFixture(fixture)) {
            this.loadGeneratedClosedObserverPistonFixture();
        } else {
            this.loadGeneratedObserverPistonFixture();
        }
    }

    private StructureFixtureControl generatedObserverPistonControl() {
        BlockPos relative = this.generatedObserverPistonLeverPos().subtract(this.volume.min());
        return new StructureFixtureControl(relative, this.generatedObserverPistonLeverPos(), Direction.UP,
                "observer sticky piston lever");
    }

    private void verifyGeneratedObserverPistonSmoke() {
        BlockPos piston = this.generatedObserverPistonBasePos();
        BlockPos observerHome = this.generatedObserverPistonObserverHomePos();
        BlockPos observerExtended = observerHome.east();

        this.record(this.level.getBlockState(piston).is(Blocks.STICKY_PISTON)
                        && !this.level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                this.currentFixture.name() + " rollback left sticky piston retracted");
        this.record(this.level.getBlockState(observerHome).is(Blocks.OBSERVER),
                this.currentFixture.name() + " rollback pulled observer back to the sticky piston");
        this.record(this.level.getBlockState(observerExtended).isAir(),
                this.currentFixture.name() + " rollback cleared the pushed observer cell");
        this.record(this.countBlocks(Blocks.PISTON_HEAD) == 0,
                this.currentFixture.name() + " rollback left no stray piston heads");
        this.record(this.countBlocks(Blocks.MOVING_PISTON) == 0,
                this.currentFixture.name() + " rollback left no moving piston placeholders");
    }

    private void verifyGeneratedClosedObserverPistonSmoke() {
        BlockPos piston = this.generatedObserverPistonBasePos();
        BlockPos observerHome = this.generatedClosedObserverPistonObserverHomePos();
        BlockPos pairedObserver = this.generatedClosedObserverPistonPairedObserverPos();
        BlockPos observerExtended = observerHome.above();

        this.record(this.level.getBlockState(piston).is(Blocks.STICKY_PISTON)
                        && !this.level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                this.currentFixture.name() + " rollback left vertical sticky piston retracted");
        this.record(this.level.getBlockState(observerHome).is(Blocks.OBSERVER),
                this.currentFixture.name() + " rollback pulled the lifted observer home");
        this.record(this.level.getBlockState(pairedObserver).is(Blocks.OBSERVER),
                this.currentFixture.name() + " rollback kept the facing observer in place");
        this.record(this.level.getBlockState(observerExtended).isAir(),
                this.currentFixture.name() + " rollback cleared the lifted observer cell");
        this.record(this.countBlocks(Blocks.OBSERVER) == 2,
                this.currentFixture.name() + " rollback left exactly two observers");
        this.record(this.countBlocks(Blocks.PISTON_HEAD) == 0,
                this.currentFixture.name() + " rollback left no orphan piston heads");
        this.record(this.countBlocks(Blocks.MOVING_PISTON) == 0,
                this.currentFixture.name() + " rollback left no moving piston placeholders");
    }

    private int countBlocks(Block block) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
            if (this.level.getBlockState(pos).is(block)) {
                count += 1;
            }
        }
        return count;
    }

    private BlockPos generatedObserverPistonLeverPos() {
        return this.volume.min().offset(2, 1, 2);
    }

    private BlockPos generatedObserverPistonBasePos() {
        return this.volume.min().offset(3, 1, 2);
    }

    private BlockPos generatedObserverPistonObserverHomePos() {
        return this.volume.min().offset(4, 1, 2);
    }

    private BlockPos generatedClosedObserverPistonObserverHomePos() {
        return this.generatedObserverPistonBasePos().above();
    }

    private BlockPos generatedClosedObserverPistonPairedObserverPos() {
        return this.generatedClosedObserverPistonObserverHomePos().east();
    }

    private boolean isGeneratedObserverPistonFixture(FixtureSpec fixture) {
        return GENERATED_OBSERVER_PISTON_FIXTURE.equals(fixture);
    }

    private boolean isGeneratedClosedObserverPistonFixture(FixtureSpec fixture) {
        return GENERATED_CLOSED_OBSERVER_PISTON_FIXTURE.equals(fixture);
    }

    private boolean isGeneratedFixture(FixtureSpec fixture) {
        return this.isGeneratedObserverPistonFixture(fixture)
                || this.isGeneratedClosedObserverPistonFixture(fixture);
    }

    private void clearVolume() {
        try (WorldMutationContext.SuppressionFrame ignored = WorldMutationContext.pushCaptureSuppression();
             WorldMutationContext.SourceFrame source = WorldMutationContext.pushSource(WorldMutationSource.RESTORE)) {
            for (Entity entity : this.level.getEntities((Entity) null, this.volume.bounds(),
                    entity -> !(entity instanceof ServerPlayer))) {
                entity.discard();
            }
            for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), FIXTURE_LOAD_FLAGS);
            }
            for (Entity entity : this.level.getEntities((Entity) null, this.volume.bounds(),
                    entity -> !(entity instanceof ServerPlayer))) {
                entity.discard();
            }
        }
    }

    private void record(boolean passed, String label) {
        this.checks.add(new StructureFixtureCheck(label, passed));
    }

    private String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String formatRelative(BlockPos pos) {
        BlockPos relative = pos.subtract(this.volume.min());
        return "+" + relative.getX() + " +" + relative.getY() + " +" + relative.getZ();
    }

    private boolean samePos(StoredBlockChange change, BlockPos pos) {
        return change != null
                && change.pos().x() == pos.getX()
                && change.pos().y() == pos.getY()
                && change.pos().z() == pos.getZ();
    }

    private String truncated(String value) {
        if (value == null || value.length() <= 120) {
            return value == null ? "" : value;
        }
        return value.substring(0, 120) + "...";
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
        WAIT_AFTER_LOAD,
        PRESS_CONTROL,
        WAIT_AFTER_CONTROL,
        CHECK_CHANGED,
        WAIT_AFTER_UNDO,
        VERIFY_UNDO,
        FINISHED
    }
}
