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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    private static final int FIXTURE_LOAD_FLAGS = 2;
    private static final int POST_OPERATION_SETTLE_TICKS = 8;
    private static final int MAX_UNDO_READY_EXTRA_TICKS = 80;

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
    private final StructureFixtureTimingPlan timingPlan = StructureFixtureTimingPlan.defaultPlan();

    private Stage stage = Stage.DISCOVER_FIXTURES;
    private List<FixtureSpec> fixtures = List.of();
    private int fixtureIndex;
    private int controlIndex;
    private int timingIndex;
    private int waitTicks;
    private int currentWaitTicks;
    private int undoReadyExtraTicks;
    private int postOperationWaitTicks;
    private FixtureSpec currentFixture;
    private List<StructureFixtureControl> controls = List.of();
    private StructureFixtureControl currentControl;
    private StructureFixtureSnapshot baseline;
    private StructureFixtureSnapshot changedSnapshot;
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
                case DISCOVER_FIXTURES -> this.discoverFixtures(server, messages);
                case PREPARE_FIXTURE -> this.prepareFixture(server, messages);
                case LOAD_CONTROL_CASE -> this.loadControlCase(server, messages);
                case PRESS_CONTROL -> this.pressControl(messages);
                case WAIT_AFTER_CONTROL -> {
                    if (this.waitTicks < this.currentWaitTicks) {
                        this.waitTicks += 1;
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
                case WAIT_AFTER_UNDO_OPERATION -> {
                    if (this.postOperationWaitTicks < POST_OPERATION_SETTLE_TICKS) {
                        this.postOperationWaitTicks += 1;
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.stage = Stage.VERIFY_UNDO;
                }
                case VERIFY_UNDO -> {
                    OperationHandle handle = this.verifyUndoAndQueueRedo(messages);
                    if (handle != null) {
                        return StructureFixtureStepResult.operation(messages, handle);
                    }
                }
                case WAIT_AFTER_REDO_OPERATION -> {
                    if (this.postOperationWaitTicks < POST_OPERATION_SETTLE_TICKS) {
                        this.postOperationWaitTicks += 1;
                        return StructureFixtureStepResult.pending(messages);
                    }
                    this.stage = Stage.VERIFY_REDO;
                }
                case VERIFY_REDO -> this.verifyRedo(messages);
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

    private void discoverFixtures(MinecraftServer server, List<String> messages) {
        List<FixtureSpec> discovered = new ArrayList<>();
        GeneratedRedstoneStructureFixtures.names().stream()
                .map(name -> new FixtureSpec(name, null))
                .forEach(discovered::add);
        discovered.addAll(this.discoverSavedFixtures(server));
        this.fixtures = List.copyOf(discovered);
        messages.add("Lumi structure fixture runner discovered " + this.fixtures.size()
                + " fixture(s), with saved .nbt fixtures under data/lumi/structure/testing");
        this.stage = Stage.PREPARE_FIXTURE;
    }

    private List<FixtureSpec> discoverSavedFixtures(MinecraftServer server) {
        return server.getResourceManager()
                .listResources(StructureFixtureResourcePath.RESOURCE_DIRECTORY,
                        id -> StructureFixtureResourcePath.isFixtureResource(id, LumaMod.MOD_ID))
                .keySet()
                .stream()
                .map(id -> StructureFixtureResourcePath.fromResourceId(id, LumaMod.MOD_ID))
                .flatMap(Optional::stream)
                .map(path -> new FixtureSpec(path.name(), path.structureId()))
                .sorted(Comparator.comparing(FixtureSpec::name))
                .toList();
    }

    private void prepareFixture(MinecraftServer server, List<String> messages) {
        if (this.fixtureIndex >= this.fixtures.size()) {
            this.stage = Stage.FINISHED;
            return;
        }

        this.currentFixture = this.fixtures.get(this.fixtureIndex);
        if (this.isGeneratedFixture(this.currentFixture)) {
            this.loadGeneratedFixture(this.currentFixture);
            this.controls = GeneratedRedstoneStructureFixtures.controls(this.currentFixture.name(), this.volume);
            this.record(true, this.currentFixture.name() + " generated redstone fixture is available");
            messages.add("Lumi structure fixture " + this.currentFixture.name()
                    + " has " + this.controls.size() + " generated control(s)");
            this.controlIndex = 0;
            this.timingIndex = 0;
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
        this.controls = StructureFixtureControl.findBlueConcreteControls(this.level, this.volume);
        messages.add("Lumi structure fixture " + this.currentFixture.name()
                + " has " + this.controls.size() + " blue-concrete control(s)");
        if (this.controls.isEmpty()) {
            this.record(true,
                    this.currentFixture.name() + " fixture has no blue-concrete control marker and was skipped");
            this.fixtureIndex += 1;
            return;
        }
        this.record(true,
                this.currentFixture.name() + " fixture exposes a button or lever on blue concrete");

        this.controlIndex = 0;
        this.timingIndex = 0;
        this.stage = Stage.LOAD_CONTROL_CASE;
    }

    private void loadControlCase(MinecraftServer server, List<String> messages) {
        if (this.timingPlan.exhausted(this.timingIndex)) {
            this.controlIndex += 1;
            this.timingIndex = 0;
        }
        if (this.controlIndex >= this.controls.size()) {
            this.fixtureIndex += 1;
            this.stage = Stage.PREPARE_FIXTURE;
            return;
        }

        this.currentWaitTicks = this.timingPlan.waitTicks(this.timingIndex);
        if (this.isGeneratedFixture(this.currentFixture)) {
            if (!this.resetLiveHistory(server)) {
                this.record(false, this.currentFixture.name() + " fixture reset live history for control "
                        + (this.controlIndex + 1) + " wait " + this.currentWaitTicks + " ticks");
                this.advanceCase();
                this.stage = Stage.LOAD_CONTROL_CASE;
                return;
            }
            this.loadGeneratedFixture(this.currentFixture);
            this.currentControl = this.controls.get(this.controlIndex).at(this.volume.min());
            this.queuedUndoAction = null;
            this.changedSnapshot = null;
            this.undoReadyExtraTicks = 0;
            this.postOperationWaitTicks = 0;
            messages.add("Lumi structure fixture " + this.currentFixture.name()
                    + " control " + (this.controlIndex + 1) + "/" + this.controls.size()
                    + " at " + this.format(this.currentControl.pos())
                    + "; testing " + this.currentWaitTicks + "-tick wait after control use");
            this.captureLoadedBaseline(server, messages);
            return;
        }

        StructureTemplate template = this.template(server, this.currentFixture);
        if (template == null) {
            this.record(false, this.currentFixture.name() + " fixture can be reloaded for control "
                    + (this.controlIndex + 1) + " wait " + this.currentWaitTicks + " ticks");
            this.fixtureIndex += 1;
            this.stage = Stage.PREPARE_FIXTURE;
            return;
        }

        if (!this.resetLiveHistory(server)) {
            this.record(false, this.currentFixture.name() + " fixture reset live history for control "
                    + (this.controlIndex + 1) + " wait " + this.currentWaitTicks + " ticks");
            this.advanceCase();
            this.stage = Stage.LOAD_CONTROL_CASE;
            return;
        }
        this.loadTemplate(template);
        this.currentControl = this.controls.get(this.controlIndex).at(this.volume.min());
        this.queuedUndoAction = null;
        this.changedSnapshot = null;
        this.undoReadyExtraTicks = 0;
        this.postOperationWaitTicks = 0;
        messages.add("Lumi structure fixture " + this.currentFixture.name()
                + " control " + (this.controlIndex + 1) + "/" + this.controls.size()
                + " at " + this.format(this.currentControl.pos())
                + "; testing " + this.currentWaitTicks + "-tick wait after control use");
        this.captureLoadedBaseline(server, messages);
    }

    private void captureLoadedBaseline(MinecraftServer server, List<String> messages) {
        if (!this.resetLiveHistory(server)) {
            this.record(false, this.currentFixture.name() + " fixture reset settled history for control "
                    + (this.controlIndex + 1) + " wait " + this.currentWaitTicks + " ticks");
            this.advanceCase();
            this.stage = Stage.LOAD_CONTROL_CASE;
            return;
        }
        this.baseline = StructureFixtureSnapshot.capture(this.level, this.volume);
        messages.add("Captured settled baseline for " + this.currentFixture.name()
                + " " + this.currentControl.label()
                + " before " + this.currentWaitTicks + "-tick control wait");
        this.stage = Stage.PRESS_CONTROL;
    }

    private void pressControl(List<String> messages) {
        boolean baselineLoaded = this.baseline.matches(StructureFixtureSnapshot.capture(this.level, this.volume));
        this.record(baselineLoaded, this.currentFixture.name() + " "
                + this.currentControl.label() + " starts from the settled loaded fixture state");

        boolean used = this.playerActions.useBlock(this.currentControl.pos(), this.currentControl.face());
        this.record(used, this.currentFixture.name() + " "
                + this.currentControl.label() + " was pressed through player interaction for "
                + this.currentWaitTicks + "-tick case");
        messages.add("Pressed " + this.currentFixture.name() + " " + this.currentControl.label()
                + "; waiting " + this.currentWaitTicks + " ticks before snapshot");
        this.waitTicks = 0;
        this.stage = Stage.WAIT_AFTER_CONTROL;
    }

    private OperationHandle checkChangedAndQueueUndo(MinecraftServer server, List<String> messages) {
        this.changedSnapshot = StructureFixtureSnapshot.capture(this.level, this.volume);
        boolean changedAfterPress = !this.baseline.matches(this.changedSnapshot);
        this.record(changedAfterPress, this.currentFixture.name() + " "
                + this.currentControl.label() + " changed blocks or entities after "
                + this.currentWaitTicks + " ticks");
        if (!changedAfterPress) {
            this.advanceCase();
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
                    + " after " + this.currentWaitTicks + "-tick snapshot");
            this.postOperationWaitTicks = 0;
            this.stage = Stage.WAIT_AFTER_UNDO_OPERATION;
            return handle;
        } catch (Exception exception) {
            if (this.isSettling(exception) && !this.requiresExactSnapshots()) {
                this.record(true, this.currentFixture.name() + " " + this.currentControl.label()
                        + " reported retryable redstone or piston settling after "
                        + this.currentWaitTicks + " ticks");
                this.advanceCase();
                this.stage = Stage.LOAD_CONTROL_CASE;
                return null;
            }
            if (this.isSettling(exception) && this.undoReadyExtraTicks < MAX_UNDO_READY_EXTRA_TICKS) {
                this.undoReadyExtraTicks += 1;
                this.currentWaitTicks += 1;
                this.stage = Stage.WAIT_AFTER_CONTROL;
                return null;
            }
            if (this.isNoUndoAction(exception) && this.savedFixtureChangedOnlyEntities()) {
                this.record(true, this.currentFixture.name() + " " + this.currentControl.label()
                        + " changed only saved-fixture entities without a live undo action after "
                        + this.currentWaitTicks + " ticks");
                messages.add("Skipped undo for saved structure fixture " + this.currentFixture.name()
                        + " because only entity snapshots changed without a live action");
                this.advanceCase();
                this.stage = Stage.LOAD_CONTROL_CASE;
                return null;
            }
            this.record(false, this.currentFixture.name() + " " + this.currentControl.label()
                    + " queued undo after " + this.currentWaitTicks
                    + " ticks: " + this.errorMessage(exception));
            this.advanceCase();
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

    private OperationHandle verifyUndoAndQueueRedo(List<String> messages) {
        StructureFixtureSnapshot restored = StructureFixtureSnapshot.capture(this.level, this.volume);
        StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy = this.comparisonPolicy();
        boolean matches = this.baseline.matches(restored, comparisonPolicy);
        if (this.requiresExactSnapshots()) {
            this.record(matches, this.currentFixture.name() + " " + this.currentControl.label()
                    + " returned exactly to the loaded fixture after undo for "
                    + this.currentWaitTicks + "-tick case"
                    + (matches ? "" : ": " + this.baseline.diff(restored, comparisonPolicy)
                    + "; " + this.describeQueuedUndoForMismatch(restored, comparisonPolicy)));
        } else {
            this.record(true, this.currentFixture.name() + " " + this.currentControl.label()
                    + " completed undo operation for " + this.currentWaitTicks + "-tick saved fixture case");
            if (!matches) {
                messages.add("Saved structure fixture " + this.currentFixture.name()
                        + " undo differed from exact dynamic snapshot: "
                        + this.baseline.diff(restored, comparisonPolicy));
            }
        }
        if (this.isGeneratedFixture(this.currentFixture)) {
            GeneratedRedstoneStructureFixtures.verifyUndoSmoke(
                    this.currentFixture.name(),
                    this.level,
                    this.volume,
                    this::record
            );
        }
        messages.add("Verified undo for " + this.currentFixture.name() + " " + this.currentControl.label());
        try {
            OperationHandle handle = this.undoRedoService.redo(this.level, this.projectName);
            messages.add("Queued Alt+Y-equivalent Lumi redo for "
                    + this.currentFixture.name() + " " + this.currentControl.label()
                    + "; verifying immediately after redo completes");
            this.postOperationWaitTicks = 0;
            this.stage = Stage.WAIT_AFTER_REDO_OPERATION;
            return handle;
        } catch (Exception exception) {
            this.record(false, this.currentFixture.name() + " " + this.currentControl.label()
                    + " queued redo after undo for " + this.currentWaitTicks
                    + "-tick case: " + this.errorMessage(exception));
            this.advanceCase();
            this.stage = Stage.LOAD_CONTROL_CASE;
            return null;
        }
    }

    private void verifyRedo(List<String> messages) {
        StructureFixtureSnapshot redone = StructureFixtureSnapshot.capture(this.level, this.volume);
        StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy = this.comparisonPolicy();
        boolean matches = this.changedSnapshot != null && this.changedSnapshot.matches(redone, comparisonPolicy);
        if (this.requiresExactSnapshots()) {
            this.record(matches, this.currentFixture.name() + " " + this.currentControl.label()
                    + " returned exactly to the " + this.currentWaitTicks
                    + "-tick changed snapshot immediately after redo"
                    + (matches ? "" : ": " + this.redoDiff(redone, comparisonPolicy)));
        } else {
            this.record(true, this.currentFixture.name() + " " + this.currentControl.label()
                    + " completed redo operation for " + this.currentWaitTicks + "-tick saved fixture case");
            if (!matches) {
                messages.add("Saved structure fixture " + this.currentFixture.name()
                        + " redo differed from exact dynamic snapshot: " + this.redoDiff(redone, comparisonPolicy));
            }
        }
        messages.add("Verified redo for " + this.currentFixture.name() + " "
                + this.currentControl.label() + " after " + this.currentWaitTicks + "-tick case");
        this.advanceCase();
        this.stage = Stage.LOAD_CONTROL_CASE;
    }

    private StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy() {
        return this.isGeneratedFixture(this.currentFixture)
                ? GeneratedRedstoneStructureFixtures.comparisonPolicy(this.currentFixture.name(), this.volume)
                : StructureFixtureSnapshot.exactComparison();
    }

    private String redoDiff(
            StructureFixtureSnapshot redone,
            StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy
    ) {
        if (this.changedSnapshot == null) {
            return "missing changed snapshot";
        }
        return this.changedSnapshot.diff(redone, comparisonPolicy);
    }

    private void advanceCase() {
        this.timingIndex += 1;
        this.waitTicks = 0;
        this.currentWaitTicks = 0;
        this.undoReadyExtraTicks = 0;
        this.postOperationWaitTicks = 0;
        this.baseline = null;
        this.changedSnapshot = null;
        this.queuedUndoAction = null;
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

    private void loadGeneratedFixture(FixtureSpec fixture) {
        this.clearVolume();
        GeneratedRedstoneStructureFixtures.load(fixture.name(), this.level, this.volume);
    }

    private boolean isGeneratedFixture(FixtureSpec fixture) {
        return fixture != null && GeneratedRedstoneStructureFixtures.isGenerated(fixture.name());
    }

    private boolean requiresExactSnapshots() {
        return this.isGeneratedFixture(this.currentFixture);
    }

    private boolean savedFixtureChangedOnlyEntities() {
        if (this.requiresExactSnapshots() || this.baseline == null || this.changedSnapshot == null) {
            return false;
        }
        StructureFixtureSnapshot.ComparisonPolicy comparisonPolicy = this.comparisonPolicy();
        return this.baseline.firstBlockMismatch(this.changedSnapshot, comparisonPolicy) == null
                && !this.baseline.matches(this.changedSnapshot, comparisonPolicy);
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

    private boolean isSettling(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message != null && message.contains("still settling");
    }

    private boolean isNoUndoAction(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message != null && message.contains("No Lumi action is available to undo");
    }

    private record FixtureSpec(String name, Identifier id) {
    }

    record StructureFixtureCheck(String label, boolean passed) {
    }

    private enum Stage {
        DISCOVER_FIXTURES,
        PREPARE_FIXTURE,
        LOAD_CONTROL_CASE,
        PRESS_CONTROL,
        WAIT_AFTER_CONTROL,
        CHECK_CHANGED,
        WAIT_AFTER_UNDO_OPERATION,
        VERIFY_UNDO,
        WAIT_AFTER_REDO_OPERATION,
        VERIFY_REDO,
        FINISHED
    }
}
