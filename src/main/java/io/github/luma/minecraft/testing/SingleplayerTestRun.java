package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.HistoryShareService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectArchiveService;
import io.github.luma.domain.service.ProjectCleanupService;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Tick-driven smoke and regression suite for real singleplayer Lumi workflows.
 */
final class SingleplayerTestRun {

    private static final String ACTOR = "Lumi singleplayer testing";
    private static final int PREVIEW_WAIT_TIMEOUT_TICKS = 20 * 60;
    private static final int EXPLOSION_WAIT_TIMEOUT_TICKS = 20 * 10;

    private final String serverKey;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final SingleplayerTestVolume volume;
    private final SingleplayerTestLog log = new SingleplayerTestLog();
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final UndoRedoService undoRedoService = new UndoRedoService();
    private final VariantService variantService = new VariantService();
    private final DiffService diffService = new DiffService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
    private final ProjectCleanupService cleanupService = new ProjectCleanupService();
    private final ProjectArchiveService archiveService = new ProjectArchiveService();
    private final HistoryShareService shareService = new HistoryShareService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final SingleplayerPerformanceMonitor performanceMonitor = new SingleplayerPerformanceMonitor();

    private Phase phase = Phase.CREATE_PROJECT;
    private Phase announcedPhase;
    private OperationHandle pendingOperation;
    private String lastOperationProgressKey = "";
    private BuildProject project;
    private ProjectVariant branch;
    private SingleplayerGameplayRegressionSuite.GameplayRegressionReport gameplayReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport explosionReport;
    private String gameplaySaveVersionId = "";
    private int previewWaitTicks;
    private int explosionWaitTicks;
    private boolean gameplaySaveValidated;
    private int phaseStartPasses;
    private int phaseStartFailures;
    private boolean done;

    SingleplayerTestRun(MinecraftServer server, ServerLevel level, ServerPlayer player, SingleplayerTestVolume volume) {
        this.serverKey = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        this.level = level;
        this.player = player;
        this.volume = volume;
        this.log.info("Reserved test volume " + this.describeVolume());
    }

    boolean matches(MinecraftServer server) {
        return this.serverKey.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
    }

    boolean done() {
        return this.done;
    }

    boolean passed() {
        return this.done && !this.log.failed();
    }

    String describeVolume() {
        return this.format(this.volume.min()) + " -> " + this.format(this.volume.max());
    }

    void message(MinecraftServer server, String text) {
        this.log.info(text);
        ServerPlayer target = server.getPlayerList().getPlayer(this.player.getUUID());
        SingleplayerTestingService.send(target, text);
    }

    void tick(MinecraftServer server) {
        if (this.done) {
            return;
        }

        Phase measuredPhase = this.phase;
        long startedAt = System.nanoTime();
        try {
            if (this.waitingForOperation(server)) {
                return;
            }

            this.announcePhase(server);
            switch (this.phase) {
                case CREATE_PROJECT -> this.createProject(server);
                case CAPTURE_DRAFT -> this.captureDraft(server);
                case START_UNDO -> this.startUndo();
                case CHECK_UNDO -> this.checkUndo();
                case START_REDO -> this.startRedo();
                case CHECK_REDO -> this.checkRedo();
                case START_SAVE -> this.startSave();
                case CHECK_SAVE -> this.checkSave(server);
                case START_AMEND -> this.startAmend();
                case CHECK_AMEND -> this.checkAmend(server);
                case START_BRANCH_SAVE -> this.startBranchSave(server);
                case CHECK_BRANCH_SAVE -> this.checkBranchSave(server);
                case START_PARTIAL_RESTORE -> this.startPartialRestore();
                case CHECK_PARTIAL_RESTORE -> this.checkPartialRestore(server);
                case START_RESTORE_INITIAL -> this.startRestoreInitial();
                case CHECK_RESTORE_INITIAL -> this.checkRestoreInitial(server);
                case CHECK_PLAYER_INTERACTIONS -> this.checkPlayerInteractions(server);
                case START_GAMEPLAY_UNDO -> this.startGameplayUndo();
                case CHECK_GAMEPLAY_UNDO -> this.checkGameplayUndo();
                case START_GAMEPLAY_REDO -> this.startGameplayRedo();
                case CHECK_GAMEPLAY_REDO -> this.checkGameplayRedo();
                case START_GAMEPLAY_SAVE -> this.startGameplaySave(server);
                case CHECK_GAMEPLAY_SAVE -> this.checkGameplaySave(server);
                case START_EXPLOSION_INTERACTION -> this.startExplosionInteraction(server);
                case CHECK_EXPLOSION_CAPTURE -> this.checkExplosionCapture(server);
                case START_EXPLOSION_UNDO -> this.startExplosionUndo();
                case CHECK_EXPLOSION_UNDO -> this.checkExplosionUndo();
                case START_EXPLOSION_REDO -> this.startExplosionRedo();
                case CHECK_EXPLOSION_REDO -> this.checkExplosionRedo();
                case START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS -> this.startRestoreInitialAfterPlayerInteractions();
                case CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS -> this.checkRestoreInitialAfterPlayerInteractions(server);
                case CHECK_PERFORMANCE -> this.checkPerformanceBudget(server);
                case CLEANUP -> this.finish(server);
            }
        } catch (Exception exception) {
            this.handlePhaseException(server, exception);
        } finally {
            this.performanceMonitor.recordSyncSlice(measuredPhase.title(), System.nanoTime() - startedAt);
        }
    }

    void fail(MinecraftServer server, Exception exception) {
        this.log.fail(this.phase.title(), "Unhandled test runner failure", exception);
        this.cleanup(server);
        this.finishWithSummary(server);
    }

    private void createProject(MinecraftServer server) throws Exception {
        String projectName = "Lumi Testing Singleplayer " + System.currentTimeMillis();
        this.project = this.projectService.createProject(this.level, projectName, this.volume.min(), this.volume.max(), ACTOR);
        this.check("Initial version v0001 was created", () -> this.projectService.loadVersions(server, projectName).size() == 1);
        this.check("Main branch metadata was created", () -> this.projectService.loadVariants(server, projectName).size() == 1);
        this.check("Fresh project integrity report is valid", () -> this.integrityService.inspect(server, projectName).valid());
        this.completePhase(server, Phase.CAPTURE_DRAFT);
    }

    private void captureDraft(MinecraftServer server) throws Exception {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerA(), Blocks.STONE.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerB(), Blocks.BARREL.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerC(), Blocks.GLASS.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }

        RecoveryDraft draft = this.value("Recovery draft can be loaded after builder edits", () ->
                this.recoveryService.loadDraft(server, this.project.name()).orElse(null));
        if (draft != null) {
            this.check(draft.totalChangeCount() >= 3, "Recovery draft captured block and block-entity changes");
        }
        VersionDiff currentDiff = this.value("Current-state diff can be built from pending draft", () ->
                this.diffService.compareVersionToCurrentState(server, this.project.name(), ProjectService.versionId(1)));
        if (currentDiff != null) {
            this.check(currentDiff.changedBlockCount() >= 3, "Current-state diff includes pending draft blocks");
            this.check(!this.materialDeltaService.summarize(currentDiff).isEmpty(), "Material delta summarizes pending draft blocks");
        }
        this.completePhase(server, Phase.START_UNDO);
    }

    private void startUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued live undo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_UNDO);
    }

    private void checkUndo() {
        this.checkAir(this.volume.markerA(), "Undo restored marker A to air");
        this.checkAir(this.volume.markerB(), "Undo restored marker B to air");
        this.checkAir(this.volume.markerC(), "Undo restored marker C to air");
        this.completePhase(this.level.getServer(), Phase.START_REDO);
    }

    private void startRedo() throws Exception {
        this.pendingOperation = this.undoRedoService.redo(this.level, this.project.name());
        this.log.info("Queued live redo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_REDO);
    }

    private void checkRedo() {
        this.checkBlock(this.volume.markerA(), Blocks.STONE, "Redo restored marker A to stone");
        this.checkBlock(this.volume.markerB(), Blocks.BARREL, "Redo restored marker B to barrel");
        this.checkBlock(this.volume.markerC(), Blocks.GLASS, "Redo restored marker C to glass");
        this.completePhase(this.level.getServer(), Phase.START_SAVE);
    }

    private void startSave() throws Exception {
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer test save", ACTOR);
        this.log.info("Queued save operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_SAVE);
    }

    private void checkSave(MinecraftServer server) throws Exception {
        this.check("Manual save created version v0002", () -> this.projectService.loadVersions(server, this.project.name()).size() == 2);
        VersionDiff diff = this.value("Saved version diff can be built", () ->
                this.diffService.compareVersionToParent(server, this.project.name(), ProjectService.versionId(2)));
        if (diff != null) {
            this.check(diff.changedBlockCount() >= 3, "Saved version patch includes captured blocks");
        }
        this.check("Save consumed the recovery draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.check("Cleanup inspection runs as dry-run", () -> this.cleanupService.inspect(server, this.project.name()).dryRun());
        this.completePhase(server, Phase.START_AMEND);
    }

    private void startAmend() throws Exception {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerC(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerD(), Blocks.COPPER_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }
        this.pendingOperation = this.versionService.startAmendVersion(this.level, this.project.name(), "Singleplayer test amend", ACTOR);
        this.log.info("Queued amend operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_AMEND);
    }

    private void checkAmend(MinecraftServer server) throws Exception {
        this.check("Amend created replacement version v0003 and kept detached v0002 for safety", () ->
                this.projectService.loadVersions(server, this.project.name()).size() == 3);
        this.check("Amend moved the main branch head to v0003", () ->
                this.projectService.loadVariants(server, this.project.name()).stream()
                        .filter(variant -> variant.main())
                        .anyMatch(variant -> ProjectService.versionId(3).equals(variant.headVersionId())));
        VersionDiff diff = this.value("Amended version diff can be built", () ->
                this.diffService.compareVersionToParent(server, this.project.name(), ProjectService.versionId(3)));
        if (diff != null) {
            this.check(diff.changedBlockCount() >= 4, "Amended version merged the new block changes");
        }
        this.checkBlock(this.volume.markerC(), Blocks.OAK_PLANKS, "Amended world marker C is oak planks");
        this.checkBlock(this.volume.markerD(), Blocks.COPPER_BLOCK, "Amended world marker D is copper block");
        this.completePhase(server, Phase.START_BRANCH_SAVE);
    }

    private void startBranchSave(MinecraftServer server) throws Exception {
        this.branch = this.variantService.createVariant(server, this.project.name(), "Testing branch", "");
        this.variantService.switchVariant(this.level, this.project.name(), this.branch.id(), false);
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(this.volume.markerA(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer test branch save", ACTOR);
        this.log.info("Queued branch save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_BRANCH_SAVE);
    }

    private void checkBranchSave(MinecraftServer server) throws Exception {
        this.project = this.projectService.loadProject(server, this.project.name());
        this.check(this.branch != null && this.branch.id().equals(this.project.activeVariantId()), "Testing branch is active");
        this.check("Branch save created version v0004", () -> this.projectService.loadVersions(server, this.project.name()).size() == 4);
        this.check("Branch diff is non-empty", () ->
                this.diffService.compareVersions(server, this.project.name(), ProjectService.versionId(3), ProjectService.versionId(4))
                        .changedBlockCount() >= 1);
        var projectArchive = this.value("Project history package can be exported", () ->
                this.archiveService.exportProject(server, this.project.name(), false));
        var branchArchive = this.value("Branch history package can be exported", () ->
                this.shareService.exportVariantPackage(server, this.project.name(), this.branch.id(), false));
        if (projectArchive != null) {
            this.check(Files.exists(projectArchive.archiveFile()) && !projectArchive.manifest().entries().isEmpty(), "Project export produced a non-empty zip");
            Files.deleteIfExists(projectArchive.archiveFile());
        }
        if (branchArchive != null) {
            this.check(Files.exists(branchArchive.archiveFile()) && branchArchive.manifest().scopeOrDefault().variantScope(), "Branch export produced a variant-scoped zip");
            Files.deleteIfExists(branchArchive.archiveFile());
        }
        this.completePhase(server, Phase.START_PARTIAL_RESTORE);
    }

    private void startPartialRestore() throws Exception {
        PartialRestoreRequest request = this.partialRestoreRequest(ProjectService.versionId(3), this.volume.markerA());
        var plan = this.value("Partial restore plan can be summarized", () ->
                this.restoreService.summarizePartialRestorePlan(this.level, request));
        if (plan != null) {
            this.check(plan.mode() != RestorePlanMode.NO_OP && plan.changedBlocks() >= 1, "Partial restore plan is actionable");
        }
        this.pendingOperation = this.restoreService.partialRestore(this.level, request);
        this.log.info("Queued partial restore operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_PARTIAL_RESTORE);
    }

    private void checkPartialRestore(MinecraftServer server) throws Exception {
        this.checkBlock(this.volume.markerA(), Blocks.STONE, "Partial restore reverted marker A to the saved stone state");
        this.check("Partial restore wrote version v0005", () -> this.projectService.loadVersions(server, this.project.name()).size() == 5);
        this.completePhase(server, Phase.START_RESTORE_INITIAL);
    }

    private void startRestoreInitial() throws Exception {
        var plan = this.value("Initial restore plan can be summarized", () ->
                this.restoreService.summarizeRestorePlan(this.level, this.project.name(), ProjectService.versionId(1)));
        if (plan != null) {
            this.check(plan.mode() != RestorePlanMode.NO_OP, "Initial restore plan is actionable");
        }
        this.pendingOperation = this.restoreService.restore(this.level, this.project.name(), ProjectService.versionId(1));
        this.log.info("Queued full restore operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_RESTORE_INITIAL);
    }

    private void checkRestoreInitial(MinecraftServer server) throws Exception {
        this.check(this.volume.isAir(this.level), "Full restore returned the test volume to initial air");
        this.check("Final integrity report is valid", () -> this.integrityService.inspect(server, this.project.name()).valid());
        this.completePhase(server, Phase.CHECK_PLAYER_INTERACTIONS);
    }

    private void checkPlayerInteractions(MinecraftServer server) throws Exception {
        SingleplayerGameplayRegressionSuite.GameplayRegressionReport report =
                new SingleplayerGameplayRegressionSuite().run(this.level, this.player, this.volume, ACTOR);
        this.gameplayReport = report;
        for (SingleplayerGameplayRegressionSuite.GameplayCheck check : report.checks()) {
            this.check(check.passed(), check.label());
        }

        RecoveryDraft draft = this.value("Live recovery draft can be loaded after gameplay actions", () ->
                HistoryCaptureManager.getInstance().snapshotDraft(server, this.project.id().toString()).orElse(null));
        if (draft != null) {
            Set<BlockPoint> capturedBlocks = new HashSet<>();
            for (var change : draft.changes()) {
                capturedBlocks.add(change.pos());
            }
            for (BlockPoint expectedBlock : report.expectedDraftBlocks()) {
                this.check(capturedBlocks.contains(expectedBlock),
                        "Gameplay draft includes block " + this.format(expectedBlock.toBlockPos()));
            }
            this.check(draft.entityChanges().size() >= report.expectedEntityChanges(),
                    "Gameplay draft includes builder-relevant entity changes");
            this.check(draft.totalChangeCount() <= 128,
                    "Gameplay draft stayed scoped instead of growing into unrelated world noise");
        }
        this.completePhase(server, Phase.START_GAMEPLAY_UNDO);
    }

    private void startGameplayUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued gameplay bridge undo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_GAMEPLAY_UNDO);
    }

    private void checkGameplayUndo() {
        if (this.gameplayReport != null) {
            for (BlockPoint expectedBlock : this.gameplayReport.latestUndoRedoBlocks()) {
                this.checkAir(expectedBlock.toBlockPos(), "Gameplay undo removed bridge block " + this.format(expectedBlock.toBlockPos()));
            }
        }
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_REDO);
    }

    private void startGameplayRedo() throws Exception {
        this.pendingOperation = this.undoRedoService.redo(this.level, this.project.name());
        this.log.info("Queued gameplay bridge redo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_GAMEPLAY_REDO);
    }

    private void checkGameplayRedo() {
        if (this.gameplayReport != null) {
            for (BlockPoint expectedBlock : this.gameplayReport.latestUndoRedoBlocks()) {
                this.checkBlock(expectedBlock.toBlockPos(), Blocks.SPRUCE_PLANKS,
                        "Gameplay redo restored bridge block " + this.format(expectedBlock.toBlockPos()));
            }
        }
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_SAVE);
    }

    private void startGameplaySave(MinecraftServer server) throws Exception {
        this.gameplaySaveVersionId = ProjectService.versionId(this.projectService.loadVersions(server, this.project.name()).size() + 1);
        this.previewWaitTicks = 0;
        this.gameplaySaveValidated = false;
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer gameplay save", ACTOR);
        this.log.info("Queued gameplay save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_GAMEPLAY_SAVE);
    }

    private void checkGameplaySave(MinecraftServer server) throws Exception {
        if (!this.gameplaySaveValidated) {
            this.check("Gameplay save created " + this.gameplaySaveVersionId, () ->
                    this.projectService.loadVersions(server, this.project.name()).stream()
                            .anyMatch(version -> this.gameplaySaveVersionId.equals(version.id())));
            VersionDiff diff = this.value("Gameplay saved version diff can be built", () ->
                    this.diffService.compareVersionToParent(server, this.project.name(), this.gameplaySaveVersionId));
            if (diff != null) {
                this.check(diff.changedBlockCount() >= Math.max(1, this.gameplayReport == null ? 0 : this.gameplayReport.latestUndoRedoBlocks().size()),
                        "Gameplay saved patch includes the water bridge");
            }
            this.check("Gameplay save consumed the recovery draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
            this.gameplaySaveValidated = true;
        }

        ProjectVersion savedVersion = this.versionById(server, this.gameplaySaveVersionId);
        if (savedVersion == null || !this.previewReady(server, savedVersion)) {
            if (++this.previewWaitTicks < PREVIEW_WAIT_TIMEOUT_TICKS) {
                return;
            }
            this.check(false, "Gameplay save preview was rendered within the timeout");
        } else {
            this.check(true, "Gameplay save preview PNG and metadata were written");
        }
        this.completePhase(server, Phase.START_EXPLOSION_INTERACTION);
    }

    private void startExplosionInteraction(MinecraftServer server) {
        this.explosionWaitTicks = 0;
        this.explosionReport = new SingleplayerExplosionRegressionScenario().start(this.level, this.player, this.volume);
        this.check(this.explosionReport.placed(), "Player placed TNT through gameMode useItemOn");
        this.check(this.explosionReport.ignited(), "Player ignited TNT through gameMode useItemOn");
        this.completePhase(server, Phase.CHECK_EXPLOSION_CAPTURE);
    }

    private void checkExplosionCapture(MinecraftServer server) throws Exception {
        if (this.explosionReport != null && !this.explosionReport.exploded(this.level)) {
            if (++this.explosionWaitTicks < EXPLOSION_WAIT_TIMEOUT_TICKS) {
                return;
            }
        }
        this.check(this.explosionReport != null && this.explosionReport.exploded(this.level),
                "Controlled TNT explosion changed the fixture");

        RecoveryDraft draft = this.value("Explosion recovery draft can be loaded", () ->
                HistoryCaptureManager.getInstance().snapshotDraft(server, this.project.id().toString()).orElse(null));
        if (draft != null && this.explosionReport != null) {
            Set<BlockPoint> capturedBlocks = new HashSet<>();
            for (var change : draft.changes()) {
                capturedBlocks.add(change.pos());
            }
            this.check(this.explosionReport.witnessBlocks().stream()
                            .map(BlockPoint::from)
                            .anyMatch(capturedBlocks::contains),
                    "Explosion draft includes at least one blast witness block");
        }
        this.completePhase(server, Phase.START_EXPLOSION_UNDO);
    }

    private void startExplosionUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued explosion undo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_EXPLOSION_UNDO);
    }

    private void checkExplosionUndo() {
        this.check(this.explosionReport != null && this.explosionReport.restoredAfterUndo(this.level),
                "Explosion undo restored TNT and blast witness blocks");
        this.completePhase(this.level.getServer(), Phase.START_EXPLOSION_REDO);
    }

    private void startExplosionRedo() throws Exception {
        this.pendingOperation = this.undoRedoService.redo(this.level, this.project.name());
        this.log.info("Queued explosion redo operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_EXPLOSION_REDO);
    }

    private void checkExplosionRedo() {
        this.check(this.explosionReport != null && this.explosionReport.removedAfterRedo(this.level),
                "Explosion redo removed TNT and blast witness blocks again");
        this.completePhase(this.level.getServer(), Phase.START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS);
    }

    private void startRestoreInitialAfterPlayerInteractions() throws Exception {
        var plan = this.value("Initial restore plan includes pending gameplay actions", () ->
                this.restoreService.summarizeRestorePlan(this.level, this.project.name(), ProjectService.versionId(1)));
        if (plan != null) {
            this.check(plan.mode() != RestorePlanMode.NO_OP, "Initial restore plan remains actionable with pending gameplay changes");
            int expectedChunks = this.gameplayReport == null ? 0 : this.gameplayReport.expectedDraftBlocks().size();
            this.check(plan.touchedChunks().size() >= Math.min(1, expectedChunks),
                    "Initial restore plan includes pending gameplay chunks");
        }
        this.pendingOperation = this.restoreService.restore(this.level, this.project.name(), ProjectService.versionId(1));
        this.log.info("Queued gameplay rollback restore operation " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS);
    }

    private void checkRestoreInitialAfterPlayerInteractions(MinecraftServer server) throws Exception {
        this.check(this.volume.isAir(this.level), "Full restore returned all gameplay blocks to initial air");
        if (this.gameplayReport != null) {
            this.check(this.gameplayReport.spawnedEntities().stream()
                            .allMatch(entity -> entity == null || entity.isRemoved()),
                    "Full restore removed gameplay entities");
            this.gameplayReport.cleanup();
            this.gameplayReport = null;
        }
        this.explosionReport = null;
        this.check("Gameplay restore consumed the recovery draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.check("Final integrity report is valid", () -> this.integrityService.inspect(server, this.project.name()).valid());
        this.completePhase(server, Phase.CHECK_PERFORMANCE);
    }

    private void checkPerformanceBudget(MinecraftServer server) {
        for (String line : this.performanceMonitor.summaryLines()) {
            this.log.info(line);
        }
        for (SingleplayerPerformanceMonitor.PerformanceCheck check : this.performanceMonitor.checks()) {
            this.check(check.passed(), check.label() + " (" + check.detail() + ")");
        }
        this.completePhase(server, Phase.CLEANUP);
    }

    private void finish(MinecraftServer server) {
        this.cleanup(server);
        this.finishWithSummary(server);
    }

    private void finishWithSummary(MinecraftServer server) {
        this.done = true;
        String result = this.log.failed() ? "completed with failures" : "passed";
        this.log.info("Final result: " + result + ", checks=" + this.log.totalChecks()
                + ", passed=" + this.log.passedChecks() + ", failed=" + this.log.failedChecks());
        Path logPath = this.writeLog(server);
        result = this.log.failed() ? "completed with failures" : "passed";
        String logText = logPath == null ? "Log write failed" : "Log: " + logPath.toAbsolutePath().normalize();
        SingleplayerTestingService.send(
                server.getPlayerList().getPlayer(this.player.getUUID()),
                "Lumi singleplayer testing " + result + ": "
                        + this.log.passedChecks() + " passed, "
                        + this.log.failedChecks() + " failed. " + logText
        );
    }

    private boolean waitingForOperation(MinecraftServer server) {
        if (this.pendingOperation == null) {
            return false;
        }
        var snapshot = this.worldOperationManager.snapshot(server, this.pendingOperation.projectId())
                .filter(candidate -> candidate.handle() != null)
                .filter(candidate -> this.pendingOperation.id().equals(candidate.handle().id()));
        if (snapshot.isEmpty()) {
            if (this.worldOperationManager.hasActiveOperation(server)) {
                return true;
            }
            this.recordFailure("World operation produced no terminal snapshot: " + this.pendingOperation.label());
            this.pendingOperation = null;
            return false;
        }

        OperationSnapshot operation = snapshot.get();
        this.performanceMonitor.recordOperationSnapshot(operation);
        if (!operation.terminal()) {
            this.reportOperationProgress(server, operation);
            return true;
        }
        if (operation.failed()) {
            this.recordFailure("World operation failed: " + this.pendingOperation.label() + " - " + operation.detail());
        } else {
            this.log.info("Operation completed: " + this.pendingOperation.label() + " " + this.pendingOperation.id());
        }
        this.pendingOperation = null;
        this.lastOperationProgressKey = "";
        return false;
    }

    private void reportOperationProgress(MinecraftServer server, OperationSnapshot operation) {
        String key = operation.stage() + ":"
                + operation.progress().completedUnits() + ":"
                + operation.progress().totalUnits() + ":"
                + operation.detail();
        if (key.equals(this.lastOperationProgressKey)) {
            return;
        }
        this.lastOperationProgressKey = key;
        this.message(server, "Lumi testing operation - "
                + operation.handle().label()
                + ": " + operation.stage()
                + " " + operation.progress().completedUnits()
                + "/" + operation.progress().totalUnits()
                + " " + operation.progress().unitLabel()
                + (operation.detail() == null || operation.detail().isBlank() ? "" : " - " + operation.detail()));
    }

    private void announcePhase(MinecraftServer server) {
        if (this.announcedPhase == this.phase) {
            return;
        }
        this.announcedPhase = this.phase;
        this.phaseStartPasses = this.log.passedChecks();
        this.phaseStartFailures = this.log.failedChecks();
        this.lastOperationProgressKey = "";
        this.message(server, "Lumi testing [" + this.phase.stepNumber() + "/" + Phase.totalSteps() + "] "
                + this.phase.title() + " - " + this.phase.description());
    }

    private void completePhase(MinecraftServer server, Phase nextPhase) {
        int passed = this.log.passedChecks() - this.phaseStartPasses;
        int failed = this.log.failedChecks() - this.phaseStartFailures;
        String status = failed == 0 ? "passed" : "completed with " + failed + " failure(s)";
        this.message(server, "Lumi testing [" + this.phase.stepNumber() + "/" + Phase.totalSteps() + "] "
                + this.phase.title() + " " + status + " (" + passed + " pass, " + failed + " fail)");
        this.phase = nextPhase;
    }

    private void handlePhaseException(MinecraftServer server, Exception exception) {
        this.log.fail(this.phase.title(), "Unexpected phase error", exception);
        this.message(server, "Lumi testing failure in " + this.phase.title() + ": " + this.errorMessage(exception));
        Phase nextPhase = this.phase.recoveryPhase(this.project != null);
        if (nextPhase == this.phase || nextPhase == Phase.CLEANUP) {
            this.phase = Phase.CLEANUP;
            return;
        }
        this.message(server, "Lumi testing continues at " + nextPhase.title());
        this.phase = nextPhase;
    }

    private PartialRestoreRequest partialRestoreRequest(String targetVersionId, BlockPos pos) {
        return new PartialRestoreRequest(
                this.project.name(),
                targetVersionId,
                io.github.luma.domain.model.Bounds3i.of(pos, pos),
                PartialRestoreRegionSource.MANUAL_BOUNDS,
                ACTOR,
                Map.of("source", "singleplayer-testing")
        );
    }

    private void cleanup(MinecraftServer server) {
        if (this.gameplayReport != null) {
            try {
                this.gameplayReport.cleanup();
            } catch (Exception exception) {
                this.log.fail(Phase.CLEANUP.title(), "Gameplay entity cleanup failed", exception);
            }
            this.gameplayReport = null;
        }
        this.explosionReport = null;
        try {
            this.clearVolume();
        } catch (Exception exception) {
            this.log.fail(Phase.CLEANUP.title(), "Test volume cleanup failed", exception);
        }
        if (this.project == null) {
            return;
        }
        try {
            HistoryCaptureManager.getInstance().discardSession(server, this.project.id().toString());
        } catch (Exception exception) {
            this.log.fail(Phase.CLEANUP.title(), "Recovery draft cleanup failed", exception);
        }
        try {
            this.projectService.setArchived(server, this.project.name(), true);
        } catch (Exception exception) {
            this.log.fail(Phase.CLEANUP.title(), "Temporary project archive failed", exception);
        }
    }

    private void clearVolume() {
        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () -> {
            for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        });
    }

    private Path writeLog(MinecraftServer server) {
        try {
            return this.log.write(server);
        } catch (Exception exception) {
            this.log.fail(Phase.CLEANUP.title(), "Writing test log failed", exception);
            SingleplayerTestingService.send(
                    server.getPlayerList().getPlayer(this.player.getUUID()),
                    "Lumi singleplayer testing could not write its log: " + this.errorMessage(exception)
            );
            return null;
        }
    }

    private void checkBlock(BlockPos pos, Block block, String label) {
        this.check(this.level.getBlockState(pos).is(block), label);
    }

    private void checkAir(BlockPos pos, String label) {
        this.check(this.level.getBlockState(pos).isAir(), label);
    }

    private ProjectVersion versionById(MinecraftServer server, String versionId) throws Exception {
        for (ProjectVersion version : this.projectService.loadVersions(server, this.project.name())) {
            if (version.id().equals(versionId)) {
                return version;
            }
        }
        return null;
    }

    private boolean previewReady(MinecraftServer server, ProjectVersion version) throws Exception {
        if (version == null
                || version.preview() == null
                || version.preview().fileName() == null
                || version.preview().fileName().isBlank()
                || version.preview().width() <= 0
                || version.preview().height() <= 0) {
            return false;
        }
        Path previewFile = this.projectService.resolveLayout(server, this.project.name()).previewFile(version.id());
        return Files.exists(previewFile) && Files.size(previewFile) > 0L;
    }

    private boolean check(String label, CheckedBooleanSupplier assertion) {
        try {
            return this.check(assertion.getAsBoolean(), label);
        } catch (Exception exception) {
            this.log.fail(this.phase.title(), label, exception);
            this.message(this.level.getServer(), "Lumi testing failure - " + this.phase.title() + ": " + label);
            return false;
        }
    }

    private boolean check(boolean condition, String label) {
        if (condition) {
            this.log.pass(this.phase.title(), label);
            return true;
        }
        this.recordFailure(label);
        return false;
    }

    private <T> T value(String label, CheckedSupplier<T> supplier) {
        try {
            T value = supplier.get();
            if (value == null) {
                this.recordFailure(label + " returned no value");
                return null;
            }
            this.log.pass(this.phase.title(), label);
            return value;
        } catch (Exception exception) {
            this.log.fail(this.phase.title(), label, exception);
            this.message(this.level.getServer(), "Lumi testing failure - " + this.phase.title() + ": " + label);
            return null;
        }
    }

    private void recordFailure(String label) {
        this.log.fail(this.phase.title(), label);
        this.message(this.level.getServer(), "Lumi testing failure - " + this.phase.title() + ": " + label);
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

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private enum Phase {
        CREATE_PROJECT("Project setup", "create the temporary project and initial snapshot"),
        CAPTURE_DRAFT("Capture and pending diff", "record builder edits and inspect pending history"),
        START_UNDO("Queue live undo", "start undo through the operation model"),
        CHECK_UNDO("Verify live undo", "check the world after undo completes"),
        START_REDO("Queue live redo", "start redo through the operation model"),
        CHECK_REDO("Verify live redo", "check the world after redo completes"),
        START_SAVE("Queue save", "save the pending tracked work"),
        CHECK_SAVE("Verify save", "inspect version, patch, draft isolation, and cleanup dry-run"),
        START_AMEND("Queue amend", "replace the active branch head with merged tracked work"),
        CHECK_AMEND("Verify amend", "inspect amended history and world state"),
        START_BRANCH_SAVE("Branch and save", "create a branch, switch to it, and save divergent work"),
        CHECK_BRANCH_SAVE("Verify branch and export", "compare branch history and export packages"),
        START_PARTIAL_RESTORE("Queue partial restore", "plan and start a selected-area restore"),
        CHECK_PARTIAL_RESTORE("Verify partial restore", "check selected-area restore output"),
        START_RESTORE_INITIAL("Queue full restore", "plan and start restore to the initial version"),
        CHECK_RESTORE_INITIAL("Verify full restore", "check final world state and project integrity"),
        CHECK_PLAYER_INTERACTIONS("Gameplay interactions", "exercise broad block, stateful block, fluid, entity, and water-bridge actions"),
        START_GAMEPLAY_UNDO("Queue gameplay undo", "undo the latest water-bridge placement through the operation model"),
        CHECK_GAMEPLAY_UNDO("Verify gameplay undo", "check that the latest water-bridge placement was removed"),
        START_GAMEPLAY_REDO("Queue gameplay redo", "redo the latest water-bridge placement through the operation model"),
        CHECK_GAMEPLAY_REDO("Verify gameplay redo", "check that the latest water-bridge placement returned"),
        START_GAMEPLAY_SAVE("Queue gameplay save", "save the player-built gameplay draft"),
        CHECK_GAMEPLAY_SAVE("Verify gameplay save", "check gameplay save output and preview fulfillment"),
        START_EXPLOSION_INTERACTION("TNT interaction", "place and ignite TNT through player game-mode actions"),
        CHECK_EXPLOSION_CAPTURE("Verify TNT capture", "wait for the controlled explosion and inspect its draft"),
        START_EXPLOSION_UNDO("Queue TNT undo", "undo the controlled explosion through the operation model"),
        CHECK_EXPLOSION_UNDO("Verify TNT undo", "check that the controlled explosion was restored"),
        START_EXPLOSION_REDO("Queue TNT redo", "redo the controlled explosion through the operation model"),
        CHECK_EXPLOSION_REDO("Verify TNT redo", "check that the controlled explosion was replayed"),
        START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS(
                "Queue gameplay rollback",
                "plan and start restore to initial after broad gameplay actions"
        ),
        CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS(
                "Verify gameplay rollback",
                "check broad gameplay actions restore to the initial world state"
        ),
        CHECK_PERFORMANCE("Performance budget", "verify the test run stayed within low-load limits"),
        CLEANUP("Cleanup and report", "remove test blocks, archive the test project, and write the log");

        private final String title;
        private final String description;

        Phase(String title, String description) {
            this.title = title;
            this.description = description;
        }

        String title() {
            return this.title;
        }

        String description() {
            return this.description;
        }

        int stepNumber() {
            return this.ordinal() + 1;
        }

        static int totalSteps() {
            return Phase.values().length;
        }

        Phase recoveryPhase(boolean projectExists) {
            if (!projectExists) {
                return CLEANUP;
            }
            return switch (this) {
                case CREATE_PROJECT, CAPTURE_DRAFT -> CLEANUP;
                case START_UNDO, CHECK_UNDO, START_REDO, CHECK_REDO -> START_SAVE;
                case START_SAVE, CHECK_SAVE -> CLEANUP;
                case START_AMEND, CHECK_AMEND -> START_BRANCH_SAVE;
                case START_BRANCH_SAVE -> START_RESTORE_INITIAL;
                case CHECK_BRANCH_SAVE -> START_PARTIAL_RESTORE;
                case START_PARTIAL_RESTORE, CHECK_PARTIAL_RESTORE -> START_RESTORE_INITIAL;
                case START_RESTORE_INITIAL, CHECK_RESTORE_INITIAL, CHECK_PLAYER_INTERACTIONS,
                     START_GAMEPLAY_UNDO, CHECK_GAMEPLAY_UNDO, START_GAMEPLAY_REDO, CHECK_GAMEPLAY_REDO,
                     START_GAMEPLAY_SAVE, CHECK_GAMEPLAY_SAVE, START_EXPLOSION_INTERACTION,
                     CHECK_EXPLOSION_CAPTURE, START_EXPLOSION_UNDO, CHECK_EXPLOSION_UNDO,
                     START_EXPLOSION_REDO, CHECK_EXPLOSION_REDO,
                     START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS, CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS,
                     CHECK_PERFORMANCE, CLEANUP -> CLEANUP;
            };
        }
    }
}
