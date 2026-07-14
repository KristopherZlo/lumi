package io.github.luma.minecraft.testing;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.SnapshotMetadata;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorldInitialBackupManifest;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.HistoryEditService;
import io.github.luma.domain.service.HistoryShareService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ProjectArchiveService;
import io.github.luma.domain.service.ProjectCleanupService;
import io.github.luma.domain.service.ProjectIntegrityService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.ProjectVersionVisibility;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VariantMergeService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.VersionRepository;
import io.github.luma.storage.repository.WorldInitialBackupRepository;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
    private final SingleplayerTestMode mode;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final SingleplayerTestVolume volume;
    private final SingleplayerTestLog log = new SingleplayerTestLog();
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final QuickRollbackService quickRollbackService = new QuickRollbackService();
    private final UndoRedoService undoRedoService = new UndoRedoService();
    private final RestoreService restoreService = new RestoreService();
    private final VariantService variantService = new VariantService();
    private final VariantMergeService variantMergeService = new VariantMergeService();
    private final DiffService diffService = new DiffService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final WorkZoneService workZoneService = new WorkZoneService();
    private final HistoryEditService historyEditService = new HistoryEditService();
    private final ProjectVersionVisibility versionVisibility = new ProjectVersionVisibility();
    private final ProjectIntegrityService integrityService = new ProjectIntegrityService();
    private final ProjectCleanupService cleanupService = new ProjectCleanupService();
    private final ProjectArchiveService archiveService = new ProjectArchiveService();
    private final HistoryShareService shareService = new HistoryShareService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final WorldOriginRepository worldOriginRepository = new WorldOriginRepository();
    private final WorldInitialBackupRepository worldInitialBackupRepository = new WorldInitialBackupRepository();
    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final SingleplayerPerformanceMonitor performanceMonitor = new SingleplayerPerformanceMonitor();

    private Phase phase;
    private Phase announcedPhase;
    private OperationHandle pendingOperation;
    private String lastOperationProgressKey = "";
    private BuildProject project;
    private BuildProject actionlessProject;
    private ProjectVariant branch;
    private SingleplayerGameplayRegressionSuite.GameplayRegressionReport gameplayReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport primedTntUndoReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport poweredTntUndoReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport chainedTntReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport explosionReport;
    private SingleplayerExplosionRegressionScenario.ExplosionRegressionReport saveDuringFuseReport;
    private SingleplayerWorldIncidentScenario.CreeperIncident creeperIncident;
    private SingleplayerWorldIncidentScenario.LightningIncident lightningIncident;
    private Set<BlockPoint> explosionDestroyedWitnessBlocks = Set.of();
    private SingleplayerBulkApplyDiagnostics bulkApplyDiagnostics;
    private SingleplayerLargeHistoryScenario largeHistoryScenario;
    private String gameplaySaveVersionId = "";
    private String gameplayEntityUpdateSaveVersionId = "";
    private String branchSaveVersionId = "";
    private String mergeVersionId = "";
    private String savedGameplayEntityId = "";
    private String workZoneId = "";
    private String workZoneSaveVersionId = "";
    private String workZoneAmendVersionId = "";
    private String concurrentSaveVersionId = "";
    private String actionlessSaveVersionId = "";
    private String saveDuringFuseActionId = "";
    private String saveDuringFuseVersionId = "";
    private BlockPos actionlessFluidPos;
    private int actionlessWaitTicks;
    private int actionlessUndoCount;
    private int workZoneEditCount;
    private int concurrentSaveStoredVersionCount;
    private BlockPoint savedGameplayEntityPosition;
    private int previewWaitTicks;
    private int explosionWaitTicks;
    private boolean gameplaySaveValidated;
    private boolean gameplayEntityUpdateSaveValidated;
    private int phaseStartPasses;
    private int phaseStartFailures;
    private boolean done;

    SingleplayerTestRun(MinecraftServer server, ServerLevel level, ServerPlayer player, SingleplayerTestVolume volume) {
        this(server, level, player, volume, SingleplayerTestMode.FULL);
    }

    SingleplayerTestRun(
            MinecraftServer server,
            ServerLevel level,
            ServerPlayer player,
            SingleplayerTestVolume volume,
            SingleplayerTestMode mode
    ) {
        this.serverKey = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        this.mode = mode == null ? SingleplayerTestMode.FULL : mode;
        this.level = level;
        this.player = player;
        this.volume = volume;
        this.phase = this.mode == SingleplayerTestMode.PLAYER_FLOW
                ? Phase.PREPARE_PLAYER_AREA
                : Phase.CREATE_PROJECT;
        this.log.info("Reserved test volume " + this.describeVolume());
        this.recordLoadSample("test-run-start");
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
                case PREPARE_PLAYER_AREA -> this.preparePlayerArea(server);
                case CREATE_PROJECT -> this.createProject(server);
                case CHECK_BOOTSTRAP_STORAGE -> this.checkBootstrapStorage(server);
                case CAPTURE_DRAFT -> this.captureDraft(server);
                case START_UNDO -> this.startUndo();
                case CHECK_UNDO -> this.checkUndo();
                case START_REDO -> this.startRedo();
                case CHECK_REDO -> this.checkRedo();
                case START_SAVE -> this.startSave();
                case CHECK_SAVE -> this.checkSave(server);
                case START_AMEND -> this.startAmend();
                case CHECK_AMEND -> this.checkAmend(server);
                case CHECK_METADATA_AND_SETTINGS -> this.checkMetadataAndSettings(server);
                case START_BRANCH_SAVE -> this.startBranchSave(server);
                case CHECK_BRANCH_SAVE -> this.checkBranchSave(server);
                case START_MERGE_TARGET_SWITCH -> this.startMergeTargetSwitch(server);
                case CHECK_MERGE_TARGET_SWITCH -> this.checkMergeTargetSwitch(server);
                case START_MERGE_SMOKE -> this.startMergeSmoke(server);
                case CHECK_MERGE_SMOKE -> this.checkMergeSmoke(server);
                case START_PARTIAL_RESTORE -> this.startPartialRestore();
                case CHECK_PARTIAL_RESTORE -> this.checkPartialRestore(server);
                case START_RESTORE_INITIAL -> this.startRestoreInitial();
                case CHECK_RESTORE_INITIAL -> this.checkRestoreInitial(server);
                case START_WORK_ZONE_SMOKE -> this.startWorkZoneSmoke(server);
                case CHECK_WORK_ZONE_SMOKE -> this.checkWorkZoneSmoke(server);
                case START_WORK_ZONE_AMEND -> this.startWorkZoneAmend(server);
                case CHECK_WORK_ZONE_AMEND -> this.checkWorkZoneAmend(server);
                case START_CONCURRENT_SAVE_SMOKE -> this.startConcurrentSaveSmoke(server);
                case CHECK_CONCURRENT_SAVE_SMOKE -> this.checkConcurrentSaveSmoke(server);
                case START_EXTERNAL_TOOL_STRESS -> this.startExternalToolStress(server);
                case CHECK_EXTERNAL_TOOL_STRESS -> this.checkExternalToolStress(server);
                case CHECK_PLAYER_INTERACTIONS -> this.checkPlayerInteractions(server);
                case START_GAMEPLAY_UNDO -> this.startGameplayUndo();
                case CHECK_GAMEPLAY_UNDO -> this.checkGameplayUndo();
                case START_GAMEPLAY_REDO -> this.startGameplayRedo();
                case CHECK_GAMEPLAY_REDO -> this.checkGameplayRedo();
                case START_GAMEPLAY_SAVE -> this.startGameplaySave(server);
                case CHECK_GAMEPLAY_SAVE -> this.checkGameplaySave(server);
                case START_ENTITY_QUICK_ROLLBACK -> this.startEntityQuickRollback(server);
                case CHECK_ENTITY_QUICK_ROLLBACK -> this.checkEntityQuickRollback(server);
                case START_GAMEPLAY_ENTITY_UPDATE_SAVE -> this.startGameplayEntityUpdateSave(server);
                case CHECK_GAMEPLAY_ENTITY_UPDATE_SAVE -> this.checkGameplayEntityUpdateSave(server);
                case START_PRIMED_TNT_UNDO_INTERACTION -> this.startPrimedTntUndoInteraction(server);
                case START_PRIMED_TNT_UNDO -> this.startPrimedTntUndo();
                case CHECK_PRIMED_TNT_UNDO -> this.checkPrimedTntUndo();
                case START_POWERED_TNT_UNDO_INTERACTION -> this.startPoweredTntUndoInteraction(server);
                case START_POWERED_TNT_UNDO -> this.startPoweredTntUndo();
                case CHECK_POWERED_TNT_UNDO -> this.checkPoweredTntUndo();
                case START_CHAINED_TNT_INTERACTION -> this.startChainedTntInteraction(server);
                case CHECK_CHAINED_TNT_CAPTURE -> this.checkChainedTntCapture(server);
                case START_CHAINED_TNT_UNDO -> this.startChainedTntUndo();
                case CHECK_CHAINED_TNT_UNDO -> this.checkChainedTntUndo();
                case CREATE_ACTIONLESS_PROJECT -> this.createActionlessProject(server);
                case START_ACTIONLESS_FLUID -> this.startActionlessFluid(server);
                case CHECK_ACTIONLESS_FLUID -> this.checkActionlessFluid(server);
                case START_ACTIONLESS_ROLLBACK -> this.startActionlessRollback();
                case CHECK_ACTIONLESS_ROLLBACK -> this.checkActionlessRollback(server);
                case START_ACTIONLESS_SAVE -> this.startActionlessSave(server);
                case CHECK_ACTIONLESS_SAVE -> this.checkActionlessSave(server);
                case START_CREEPER_INCIDENT -> this.startCreeperIncident(server);
                case START_CREEPER_INCIDENT_UNDO -> this.startCreeperIncidentUndo();
                case CHECK_CREEPER_INCIDENT_UNDO -> this.checkCreeperIncidentUndo(server);
                case START_LIGHTNING_INCIDENT -> this.startLightningIncident(server);
                case START_LIGHTNING_INCIDENT_UNDO -> this.startLightningIncidentUndo();
                case CHECK_LIGHTNING_INCIDENT_UNDO -> this.checkLightningIncidentUndo(server);
                case START_EXPLOSION_INTERACTION -> this.startExplosionInteraction(server);
                case CHECK_EXPLOSION_CAPTURE -> this.checkExplosionCapture(server);
                case START_EXPLOSION_UNDO -> this.startExplosionUndo();
                case CHECK_EXPLOSION_UNDO -> this.checkExplosionUndo();
                case START_SAVE_DURING_TNT_FUSE -> this.startSaveDuringTntFuse(server);
                case CHECK_SAVE_DURING_TNT_FUSE -> this.checkSaveDuringTntFuse(server);
                case START_SAVE_DURING_TNT_ROLLBACK -> this.startSaveDuringTntRollback();
                case CHECK_SAVE_DURING_TNT_ROLLBACK -> this.checkSaveDuringTntRollback(server);
                case START_EXPLOSION_REDO -> this.startExplosionRedo();
                case CHECK_EXPLOSION_REDO -> this.checkExplosionRedo();
                case START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS -> this.startRestoreInitialAfterPlayerInteractions();
                case CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS -> this.checkRestoreInitialAfterPlayerInteractions(server);
                case CHECK_PERFORMANCE -> this.checkPerformanceBudget(server);
                case START_LARGE_HISTORY_DIAGNOSTICS -> this.startLargeHistoryDiagnostics(server);
                case RUN_LARGE_HISTORY_DIAGNOSTICS -> this.runLargeHistoryDiagnostics(server);
                case START_BULK_APPLY_DIAGNOSTICS -> this.startBulkApplyDiagnostics(server);
                case RUN_BULK_APPLY_DIAGNOSTICS -> this.runBulkApplyDiagnostics(server);
                case START_STRUCTURE_FIXTURE_TESTS -> this.startStructureFixtureTests(server);
                case RUN_STRUCTURE_FIXTURE_TESTS -> this.runStructureFixtureTests(server);
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

    private void preparePlayerArea(MinecraftServer server) {
        String generatorName = this.chunkGeneratorName();
        this.log.info("Player-flow source world generator=" + generatorName);
        this.check(
                !generatorName.toLowerCase(Locale.ROOT).contains("flat"),
                "Player-flow source world is not using a flat chunk generator"
        );

        WorldMutationContext.runWithSource(WorldMutationSource.RESTORE, () ->
                this.volume.preparePlayerPlatform(this.level));
        BlockPos standOnPlatform = this.volume.min().offset(1, 1, 1);
        this.player.teleportTo(
                standOnPlatform.getX() + 0.5D,
                standOnPlatform.getY(),
                standOnPlatform.getZ() + 0.5D
        );
        this.player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        this.player.snapTo(
                standOnPlatform.getX() + 0.5D,
                standOnPlatform.getY(),
                standOnPlatform.getZ() + 0.5D,
                180.0F,
                20.0F
        );
        this.check(
                this.volume.isPreparedPlayerPlatform(this.level),
                "Player-flow prepared a smooth-stone build platform with clear working air"
        );
        this.completePhase(server, Phase.CREATE_PROJECT);
    }

    private void createProject(MinecraftServer server) throws Exception {
        String projectName = "Lumi Testing Singleplayer " + System.currentTimeMillis();
        this.project = this.projectService.createProject(this.level, projectName, this.volume.min(), this.volume.max(), ACTOR);
        this.check("Initial version v0001 was created", () -> this.projectService.loadVersions(server, projectName).size() == 1);
        this.check("Main branch metadata was created", () -> this.projectService.loadVariants(server, projectName).size() == 1);
        this.check("Fresh project integrity report is valid", () -> this.integrityService.inspect(server, projectName).valid());
        this.completePhase(server, this.mode == SingleplayerTestMode.STRUCTURE_FIXTURES
                ? Phase.START_STRUCTURE_FIXTURE_TESTS
                : Phase.CHECK_BOOTSTRAP_STORAGE);
    }

    private void checkBootstrapStorage(MinecraftServer server) throws Exception {
        this.projectService.bootstrapWorld(server);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        WorldOriginInfo origin = this.value("World-origin manifest can be loaded after bootstrap", () ->
                this.worldOriginRepository.load(server).orElse(null));
        WorldInitialBackupManifest backup = this.value("Pre-open checkpoint manifest can be loaded after bootstrap", () ->
                this.worldInitialBackupRepository.load(worldRoot).orElse(null));

        if (origin != null && backup != null) {
            this.check(backup.completedForSeed(origin.seed()), "Pre-open checkpoint manifest completed for the current seed");
            this.check(backup.maxCompressedBytes() >= 0L, "Pre-open checkpoint manifest records a storage budget");
            long compressedBytes = backup.dimensions().values().stream()
                    .mapToLong(WorldInitialBackupManifest.DimensionBackupSummary::compressedBytes)
                    .sum();
            this.check(backup.maxCompressedBytes() <= 0L || compressedBytes <= backup.maxCompressedBytes(),
                    "Opt-in pre-mod backup compressed bytes stayed within budget");
            this.check(backup.dimensions().values().stream()
                            .allMatch(summary -> summary.backedUpChunks() <= summary.scannedChunks()),
                    "Opt-in pre-mod backup never wrote more chunks than it scanned");
        }

        ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
        SnapshotMetadata initialSnapshot = this.value("Initial snapshot section index can be loaded", () ->
                this.snapshotReader.loadSectionIndex(layout.snapshotFile(ProjectService.snapshotId(1))));
        if (initialSnapshot != null) {
            this.check(!initialSnapshot.chunks().isEmpty(), "Initial snapshot records chunk-addressable metadata");
            if (initialSnapshot.sectionCount() > 0) {
                this.check(initialSnapshot.chunks().stream()
                                .filter(chunk -> !chunk.sectionFingerprints().isEmpty())
                                .allMatch(chunk -> !chunk.contentRefs().isEmpty()),
                        "Initial snapshot section metadata includes content refs");
            }
        }
        this.completePhase(server, Phase.CAPTURE_DRAFT);
    }

    private void captureDraft(MinecraftServer server) throws Exception {
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            long interactionStartedAt = System.nanoTime();
            long interactionCpuStartedAt = SingleplayerPerformanceMonitor.currentThreadCpuNanos();
            try {
                this.level.setBlock(this.volume.markerA(), Blocks.STONE.defaultBlockState(), 3);
            } finally {
                this.recordFirstInteraction("First capture setBlock", interactionStartedAt, interactionCpuStartedAt);
            }
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
        this.completePhase(server, Phase.START_SAVE);
    }

    private void startUndo() throws Exception {
        this.completePhase(this.level.getServer(), Phase.START_SAVE);
    }

    private void checkUndo() {
        this.completePhase(this.level.getServer(), Phase.START_SAVE);
    }

    private void startRedo() throws Exception {
        this.completePhase(this.level.getServer(), Phase.START_SAVE);
    }

    private void checkRedo() {
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
        ProjectVersion savedVersion = this.versionById(server, ProjectService.versionId(2));
        if (savedVersion != null && !savedVersion.patchIds().isEmpty()) {
            ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
            var metadata = this.value("Saved patch metadata can be loaded", () ->
                    this.patchMetaRepository.load(layout, savedVersion.patchIds().getFirst()).orElse(null));
            if (metadata != null) {
                SectionFingerprint selectedSection = metadata.chunks().stream()
                        .flatMap(chunk -> chunk.sectionFingerprints().stream())
                        .findFirst()
                        .orElse(null);
                this.check(selectedSection != null, "Saved patch metadata includes section fingerprints");
                if (selectedSection != null) {
                    var selectedChanges = this.value("Saved patch selected-section payload can be read", () ->
                            this.patchDataRepository.loadWorldChangesForSections(
                                    layout,
                                    metadata,
                                    java.util.List.of(selectedSection)
                            ));
                    if (selectedChanges != null) {
                        this.check(!selectedChanges.blockChanges().isEmpty(),
                                "Selected-section patch read materializes block changes");
                    }
                }
            }
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
        this.check("Amend hid detached v0002 from visible history", () ->
                this.projectService.loadVersions(server, this.project.name()).stream()
                        .noneMatch(version -> ProjectService.versionId(2).equals(version.id())));
        this.check("Amend created visible replacement version v0003", () ->
                this.projectService.loadVersions(server, this.project.name()).stream()
                        .anyMatch(version -> ProjectService.versionId(3).equals(version.id())));
        this.check("Amend kept detached v0002 on disk", () ->
                this.storedVersionById(server, ProjectService.versionId(2)) != null);
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
        this.completePhase(server, Phase.CHECK_METADATA_AND_SETTINGS);
    }

    private void checkMetadataAndSettings(MinecraftServer server) throws Exception {
        String amendVersionId = ProjectService.versionId(3);
        this.historyEditService.renameVersion(server, this.project.name(), amendVersionId, "Renamed singleplayer amend");
        this.historyEditService.updateVersionTags(server, this.project.name(), amendVersionId, List.of("Smoke", "#Amend", "smoke"));
        ProjectVersion renamed = this.versionById(server, amendVersionId);
        this.check(renamed != null && "Renamed singleplayer amend".equals(renamed.message()),
                "Rename updates visible history metadata only");
        this.check(renamed != null && ProjectVersionTags.from(renamed).equals(List.of("smoke", "amend")),
                "Tag update normalizes and deduplicates tags");
        this.check("Metadata edits did not create another visible version", () ->
                this.projectService.loadVersions(server, this.project.name()).size() == 2);

        ProjectSettings smokeSettings = new ProjectSettings(
                true,
                0,
                0,
                0,
                0.0D,
                false,
                false,
                true,
                true,
                0,
                false
        );
        this.projectService.updateSettings(server, this.project.name(), smokeSettings);
        ProjectSettings loaded = this.projectService.loadProject(server, this.project.name()).settings();
        this.check(loaded.autoVersionsEnabled(), "Settings persist auto-version toggle");
        this.check(loaded.autoVersionMinutes() == 10, "Settings sanitize auto-version minutes");
        this.check(loaded.sessionIdleSeconds() == 5, "Settings sanitize idle seconds");
        this.check(loaded.snapshotEveryVersions() == 10, "Settings sanitize snapshot interval");
        this.check(loaded.snapshotVolumeThreshold() == 0.20D, "Settings sanitize snapshot threshold");
        this.check(!loaded.safetySnapshotBeforeRestore(), "Settings persist restore safety toggle");
        this.check(!loaded.previewGenerationEnabled(), "Settings persist preview toggle");
        this.check(loaded.debugLoggingEnabled(), "Settings persist debug logging toggle");
        this.check(loaded.autoCheckpointEnabled(), "Settings persist auto-checkpoint toggle");
        this.check(loaded.autoCheckpointLargeChangeThreshold() == ProjectSettings.DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD,
                "Settings sanitize auto-checkpoint threshold");
        this.check(!loaded.workspaceHudVisible(), "Settings persist workspace HUD toggle");
        this.project = this.projectService.updateSettings(server, this.project.name(), ProjectSettings.defaults());
        this.completePhase(server, Phase.START_BRANCH_SAVE);
    }

    private void startBranchSave(MinecraftServer server) throws Exception {
        this.branch = this.variantService.createVariant(server, this.project.name(), "Testing branch", "");
        this.variantService.activateVariantMetadataOnlyForTesting(server, this.project.name(), this.branch.id());
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
                this.level.setBlock(this.volume.markerA(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }
        this.branchSaveVersionId = this.nextVersionId(server);
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer test branch save", ACTOR);
        this.log.info("Queued branch save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_BRANCH_SAVE);
    }

    private void checkBranchSave(MinecraftServer server) throws Exception {
        this.project = this.projectService.loadProject(server, this.project.name());
        this.check(this.branch != null && this.branch.id().equals(this.project.activeVariantId()), "Testing branch is active");
        this.check("Branch save created " + this.branchSaveVersionId, () -> this.versionById(server, this.branchSaveVersionId) != null);
        this.check("Branch history keeps detached amend head hidden", () -> this.projectService.loadVersions(server, this.project.name()).size() == 3);
        this.check("Branch diff is non-empty", () ->
                this.diffService.compareVersions(server, this.project.name(), ProjectService.versionId(3), this.branchSaveVersionId)
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
        this.completePhase(server, Phase.START_MERGE_TARGET_SWITCH);
    }

    private void startMergeTargetSwitch(MinecraftServer server) throws Exception {
        this.variantService.switchVariant(this.level, this.project.name(), "main");
        this.pendingOperation = this.worldOperationManager.snapshot(server)
                .map(OperationSnapshot::handle)
                .orElseThrow(() -> new IllegalStateException("Variant switch did not start an operation"));
        this.log.info("Queued merge target switch operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_MERGE_TARGET_SWITCH);
    }

    private void checkMergeTargetSwitch(MinecraftServer server) throws Exception {
        this.project = this.projectService.loadProject(server, this.project.name());
        this.check("Main branch is active before merge", () -> "main".equals(this.project.activeVariantId()));
        this.checkBlock(this.volume.markerA(), Blocks.STONE, "Switching to main restored marker A before merge");
        this.completePhase(server, Phase.START_MERGE_SMOKE);
    }

    private void startMergeSmoke(MinecraftServer server) throws Exception {
        var plan = this.variantMergeService.previewLocalMerge(server, this.project.name(), this.branch.id());
        this.check(!plan.hasConflicts(), "Local merge smoke plan has no conflicts");
        this.check(plan.mergeChangeCount() >= 1, "Local merge smoke plan has source changes");
        if (plan.hasConflicts() || plan.mergeChangeCount() < 1) {
            this.completePhase(server, Phase.CLEANUP);
            return;
        }
        this.mergeVersionId = this.nextVersionId(server);
        this.pendingOperation = this.variantMergeService.startLocalMerge(this.level, this.project.name(), this.branch.id(), List.of(), ACTOR);
        this.log.info("Queued local merge operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_MERGE_SMOKE);
    }

    private void checkMergeSmoke(MinecraftServer server) throws Exception {
        ProjectVersion merged = this.versionById(server, this.mergeVersionId);
        this.check(merged != null && merged.versionKind() == VersionKind.MERGE, "Local merge wrote a merge version");
        this.check("Local merge moved only the active main head", () ->
                this.projectService.loadVariants(server, this.project.name()).stream()
                        .anyMatch(variant -> variant.main() && this.mergeVersionId.equals(variant.headVersionId())));
        this.check("Local merge left source branch head unchanged", () ->
                this.projectService.loadVariants(server, this.project.name()).stream()
                        .anyMatch(variant -> this.branch.id().equals(variant.id())
                                && this.branchSaveVersionId.equals(variant.headVersionId())));
        this.checkBlock(this.volume.markerA(), Blocks.GOLD_BLOCK, "Local merge applied source branch block");
        this.check("Local merge consumed no draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
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
        this.check("Partial restore kept the saved version count unchanged", () ->
                this.projectService.loadVersions(server, this.project.name()).size() == 4);
        this.check("Partial restore left the applied region as pending work", () ->
                this.recoveryService.loadDraft(server, this.project.name()).isPresent());
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
        this.checkInitialVolumeRestored(this.mode == SingleplayerTestMode.PLAYER_FLOW
                ? "Full restore returned the test volume to the prepared platform baseline"
                : "Full restore returned the test volume to initial air");
        this.check("Final integrity report is valid", () -> this.integrityService.inspect(server, this.project.name()).valid());
        this.completePhase(server, Phase.START_WORK_ZONE_SMOKE);
    }

    private void startWorkZoneSmoke(MinecraftServer server) throws Exception {
        ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
        WorkZone zone = this.workZoneService.createZone(
                layout,
                this.project.id().toString(),
                "Singleplayer smoke zone",
                ACTOR,
                java.time.Instant.now()
        );
        this.workZoneId = zone.id();
        this.workZoneSaveVersionId = this.nextVersionId(server);
        this.workZoneEditCount = 0;

        Random random = new Random(0x5104E5L);
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            for (int index = 0; index < 96; index++) {
                BlockPos pos = this.randomZoneSmokePos(random);
                this.level.setBlock(pos, this.zoneSmokeBlock(index).defaultBlockState(), 3);
                this.workZoneService.touchBlock(layout, ACTOR, BlockPoint.from(pos), java.time.Instant.now());
                this.workZoneEditCount += 1;
            }
        } finally {
            WorldMutationContext.popSource();
        }

        this.pendingOperation = this.versionService.startSaveVersion(
                this.level,
                this.project.name(),
                "Singleplayer work-zone smoke save",
                ACTOR
        );
        this.log.info("Queued work-zone smoke save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_WORK_ZONE_SMOKE);
    }

    private void checkWorkZoneSmoke(MinecraftServer server) throws Exception {
        ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
        ProjectVersion savedVersion = this.versionById(server, this.workZoneSaveVersionId);
        this.check(savedVersion != null, "Work-zone smoke save created " + this.workZoneSaveVersionId);
        if (savedVersion != null) {
            this.check(this.workZoneId.equals(this.versionVisibility.workZoneId(savedVersion)),
                    "Work-zone smoke save records zone metadata");
            VersionDiff diff = this.value("Work-zone smoke save diff can be built", () ->
                    this.diffService.compareVersionToParent(server, this.project.name(), savedVersion.id()));
            if (diff != null) {
                this.check(diff.changedBlockCount() >= Math.min(8, this.workZoneEditCount),
                        "Work-zone smoke save persisted randomized zone edits");
            }
        }
        this.check("Zone history query returns the smoke save", () ->
                this.projectService.loadWorkZoneVersions(server, this.project.name()).stream()
                        .anyMatch(version -> version.id().equals(this.workZoneSaveVersionId)));
        this.check("Work-zone cell state was persisted", () ->
                this.workZoneService.load(layout).zones().stream()
                        .filter(zone -> zone.id().equals(this.workZoneId))
                        .anyMatch(zone -> !zone.cells().isEmpty()));
        this.check("Work-zone smoke save consumed its draft", () ->
                this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.completePhase(server, Phase.START_WORK_ZONE_AMEND);
    }

    private void startWorkZoneAmend(MinecraftServer server) throws Exception {
        ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
        this.workZoneService.selectZone(layout, ACTOR, this.workZoneId);
        BlockPos pos = this.volume.markerB();
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            this.workZoneService.touchBlock(layout, ACTOR, BlockPoint.from(pos), java.time.Instant.now());
        } finally {
            WorldMutationContext.popSource();
        }
        this.workZoneAmendVersionId = this.nextVersionId(server);
        this.pendingOperation = this.versionService.startAmendVersion(
                this.level,
                this.project.name(),
                "Singleplayer work-zone smoke amend",
                ACTOR,
                List.of("zone-amend")
        );
        this.log.info("Queued work-zone amend operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_WORK_ZONE_AMEND);
    }

    private void checkWorkZoneAmend(MinecraftServer server) throws Exception {
        ProjectLayout layout = this.projectService.resolveLayout(server, this.project.name());
        ProjectVersion amended = this.versionById(server, this.workZoneAmendVersionId);
        this.check(amended != null, "Work-zone amend created " + this.workZoneAmendVersionId);
        this.check("Work-zone amend detached the previous zone head from visible history", () ->
                this.projectService.loadVersions(server, this.project.name()).stream()
                        .noneMatch(version -> this.workZoneSaveVersionId.equals(version.id())));
        this.check("Work-zone amend kept the previous zone head on disk", () ->
                this.storedVersionById(server, this.workZoneSaveVersionId) != null);
        this.check("Zone history shows amended head only", () ->
                this.projectService.loadWorkZoneVersions(server, this.project.name()).stream()
                        .anyMatch(version -> this.workZoneAmendVersionId.equals(version.id()))
                        && this.projectService.loadWorkZoneVersions(server, this.project.name()).stream()
                        .noneMatch(version -> this.workZoneSaveVersionId.equals(version.id())));
        this.check(amended != null && ProjectVersionTags.from(amended).contains("zone-amend"),
                "Work-zone amend persists tags");
        this.checkBlock(this.volume.markerB(), Blocks.DIAMOND_BLOCK, "Work-zone amend applied selected zone edit");
        this.check("Work-zone amend consumed its draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.workZoneService.selectZone(layout, ACTOR, "");
        this.completePhase(server, Phase.START_CONCURRENT_SAVE_SMOKE);
    }

    private void startConcurrentSaveSmoke(MinecraftServer server) throws Exception {
        BlockPos pos = this.volume.min().offset(12, 1, 12);
        WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true);
        try {
            this.level.setBlock(pos, Blocks.LAPIS_BLOCK.defaultBlockState(), 3);
        } finally {
            WorldMutationContext.popSource();
        }

        this.concurrentSaveStoredVersionCount = this.storedVersionCount(server);
        this.concurrentSaveVersionId = this.nextVersionId(server);
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer concurrent save winner", ACTOR);
        try {
            this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer concurrent save loser", ACTOR);
            this.recordFailure("Concurrent save rejected the second save");
        } catch (IllegalStateException exception) {
            this.check(exception.getMessage() != null
                    && exception.getMessage().contains("Another world operation is already running"),
                    "Concurrent save rejected the second save as busy");
        }
        this.log.info("Queued concurrent save winner operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_CONCURRENT_SAVE_SMOKE);
    }

    private void checkConcurrentSaveSmoke(MinecraftServer server) throws Exception {
        this.check("Concurrent save created only one stored version", () ->
                this.storedVersionCount(server) == this.concurrentSaveStoredVersionCount + 1);
        this.check("Concurrent save winner is visible", () -> this.versionById(server, this.concurrentSaveVersionId) != null);
        this.checkBlock(this.volume.min().offset(12, 1, 12), Blocks.LAPIS_BLOCK, "Concurrent save winner block is in world");
        this.check("Concurrent save consumed the draft once", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());

        Phase next = switch (this.mode) {
            case LOAD_SMOKE -> Phase.CHECK_PERFORMANCE;
            case SMOKE, CRASH_SAFETY -> Phase.CLEANUP;
            case EXTERNAL_TOOLS -> Phase.START_EXTERNAL_TOOL_STRESS;
            default -> Phase.CHECK_PLAYER_INTERACTIONS;
        };
        this.completePhase(server, next);
    }

    private BlockPos randomZoneSmokePos(Random random) {
        return this.volume.min().offset(
                1 + random.nextInt(Math.max(1, SingleplayerTestVolume.WIDTH - 2)),
                1 + random.nextInt(Math.max(1, SingleplayerTestVolume.HEIGHT - 2)),
                1 + random.nextInt(Math.max(1, SingleplayerTestVolume.DEPTH - 2))
        );
    }

    private Block zoneSmokeBlock(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> Blocks.STONE;
            case 1 -> Blocks.OAK_PLANKS;
            case 2 -> Blocks.COPPER_BLOCK;
            default -> Blocks.GLASS;
        };
    }

    private void startExternalToolStress(MinecraftServer server) throws Exception {
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                WorldMutationSource.WORLDEDIT,
                "worldedit:singleplayer-test",
                "worldedit-fill",
                true
        )) {
            for (BlockPos pos : BlockPos.betweenClosed(this.volume.min(), this.volume.max())) {
                this.level.setBlock(pos, Blocks.DEEPSLATE.defaultBlockState(), 3);
            }
        }
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushExternalSource(
                WorldMutationSource.AXIOM,
                "axiom:singleplayer-test",
                "axiom-buffer",
                true
        )) {
            this.level.setBlock(this.volume.markerA(), Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
            this.level.setBlock(this.volume.markerB(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        }

        RecoveryDraft draft = this.value("External-tool recovery draft can be loaded", () ->
                this.recoveryService.loadDraft(server, this.project.name()).orElse(null));
        if (draft != null) {
            this.check(draft.totalChangeCount() >= 3, "External-tool draft captured WorldEdit and Axiom-source edits");
        }
        this.pendingOperation = this.versionService.startSaveVersion(
                this.level,
                this.project.name(),
                "Singleplayer external tool save",
                ACTOR
        );
        this.log.info("Queued external-tool save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_EXTERNAL_TOOL_STRESS);
    }

    private void checkExternalToolStress(MinecraftServer server) throws Exception {
        ProjectVersion savedVersion = this.projectService.loadVersions(server, this.project.name()).getLast();
        VersionDiff diff = this.value("External-tool saved version diff can be built", () ->
                this.diffService.compareVersionToParent(server, this.project.name(), savedVersion.id()));
        if (diff != null) {
            this.check(diff.changedBlockCount() >= 3, "External-tool save persisted captured edits");
        }
        this.check("External-tool project integrity report is valid", () ->
                this.integrityService.inspect(server, this.project.name()).valid());
        this.completePhase(server, Phase.CLEANUP);
    }

    private void checkPlayerInteractions(MinecraftServer server) throws Exception {
        SingleplayerGameplayRegressionSuite.GameplayRegressionReport report =
                new SingleplayerGameplayRegressionSuite().run(this.level, this.player, this.volume, ACTOR);
        this.gameplayReport = report;
        for (SingleplayerGameplayRegressionSuite.GameplayCheck check : report.checks()) {
            this.check(check.passed(), check.label());
        }
        for (SingleplayerGameplayRegressionSuite.GameplayTiming timing : report.timings()) {
            this.log.info("Gameplay timing: scenario=" + timing.scenario()
                    + ", durationMs=" + timing.durationMillis());
        }
        EntityMutationTracker.tick(server);

        RecoveryDraft draft = this.value("Live recovery draft can be loaded after gameplay actions", () ->
                HistoryCaptureManager.getInstance().snapshotDraft(server, this.project.id().toString()).orElse(null));
        if (draft != null) {
            Set<BlockPoint> capturedBlocks = new HashSet<>();
            Map<BlockPoint, io.github.luma.domain.model.StoredBlockChange> capturedBlockChanges = new HashMap<>();
            for (var change : draft.changes()) {
                capturedBlocks.add(change.pos());
                capturedBlockChanges.put(change.pos(), change);
            }
            for (BlockPoint expectedBlock : report.expectedDraftBlocks()) {
                this.check(capturedBlocks.contains(expectedBlock),
                        "Gameplay draft includes block " + this.format(expectedBlock.toBlockPos()));
            }
            for (Map.Entry<BlockPoint, Map<String, String>> expectedState : report.expectedDraftProperties().entrySet()) {
                var change = capturedBlockChanges.get(expectedState.getKey());
                for (Map.Entry<String, String> expectedProperty : expectedState.getValue().entrySet()) {
                    this.check(expectedProperty.getValue().equals(this.stateProperty(change, expectedProperty.getKey())),
                            "Gameplay draft stores " + expectedProperty.getKey()
                                    + "=" + expectedProperty.getValue()
                                    + " at " + this.format(expectedState.getKey().toBlockPos()));
                }
            }
            for (BlockPoint unexpectedBlock : report.unexpectedDraftBlocks()) {
                this.check(!capturedBlocks.contains(unexpectedBlock),
                        "Gameplay draft excludes transient block " + this.format(unexpectedBlock.toBlockPos()));
            }
            this.check(draft.entityChanges().size() >= report.expectedEntityChanges(),
                    "Gameplay draft includes expected entity changes");
            Map<String, io.github.luma.domain.model.StoredEntityChange> capturedEntityChanges = new HashMap<>();
            for (var change : draft.entityChanges()) {
                capturedEntityChanges.put(change.entityId(), change);
            }
            Set<String> capturedEntityIds = capturedEntityChanges.keySet();
            for (String expectedEntityId : report.expectedEntityIds()) {
                this.check(capturedEntityIds.contains(expectedEntityId),
                        "Gameplay draft includes entity " + expectedEntityId);
            }
            for (Map.Entry<String, BlockPoint> entry : report.expectedEntityPositions().entrySet()) {
                var change = capturedEntityChanges.get(entry.getKey());
                BlockPoint capturedPosition = change == null || change.newValue() == null
                        ? null
                        : BlockPoint.from(change.newValue().blockPos());
                this.check(entry.getValue().equals(capturedPosition),
                        "Gameplay draft stores entity position " + this.format(entry.getValue().toBlockPos()));
            }
            this.check(draft.totalChangeCount() <= 128,
                    "Gameplay draft stayed scoped instead of growing into unrelated world noise");
        }
        this.completePhase(server, Phase.START_GAMEPLAY_SAVE);
    }

    private void startGameplayUndo() throws Exception {
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_SAVE);
    }

    private void checkGameplayUndo() {
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_SAVE);
    }

    private void startGameplayRedo() throws Exception {
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_SAVE);
    }

    private void checkGameplayRedo() {
        this.completePhase(this.level.getServer(), Phase.START_GAMEPLAY_SAVE);
    }

    private void startGameplaySave(MinecraftServer server) throws Exception {
        this.gameplaySaveVersionId = this.nextVersionId(server);
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
                this.check(diff.changedBlockCount() >= Math.max(1, this.gameplayReport == null ? 0 : this.gameplayReport.latestCaptureBlocks().size()),
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
        this.completePhase(server, Phase.START_ENTITY_QUICK_ROLLBACK);
    }

    private void startEntityQuickRollback(MinecraftServer server) throws Exception {
        Entity entity = this.gameplayEntity();
        if (entity == null) {
            this.recordFailure("Gameplay saved entity is available for quick rollback");
            this.completePhase(server, Phase.START_EXPLOSION_INTERACTION);
            return;
        }

        this.savedGameplayEntityId = entity.getStringUUID();
        this.savedGameplayEntityPosition = BlockPoint.from(entity.blockPosition());
        BlockPos moved = this.volume.min().offset(9, 1, 9);
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true)) {
            entity.snapTo(moved.getX() + 0.5D, moved.getY(), moved.getZ() + 0.5D, 180.0F, 0.0F);
            entity.setCustomName(Component.literal("lumi-quickrollback-mutated"));
        }
        this.check(entity.blockPosition().equals(moved), "Gameplay entity pending update moved before quick rollback");
        this.pendingOperation = this.quickRollbackService.quickRollback(this.level, this.project.name());
        this.log.info("Queued entity quick rollback operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_ENTITY_QUICK_ROLLBACK);
    }

    private void checkEntityQuickRollback(MinecraftServer server) {
        Entity entity = this.entityById(this.savedGameplayEntityId);
        this.check(entity != null && !entity.isRemoved(), "Quick rollback kept the saved gameplay entity alive");
        if (entity != null) {
            BlockPoint restoredPosition = BlockPoint.from(entity.blockPosition());
            this.check(this.savedGameplayEntityPosition != null
                            && this.savedGameplayEntityPosition.equals(restoredPosition),
                    "Quick rollback restored gameplay entity position expected="
                            + this.savedGameplayEntityPosition + " actual=" + restoredPosition);
            this.check(entity.hasCustomName()
                            && "lumi-runtime-entity".equals(entity.getCustomName().getString()),
                    "Quick rollback restored gameplay entity name");
        }
        this.check("Quick rollback did not create a replay recovery draft",
                () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.completePhase(server, Phase.START_GAMEPLAY_ENTITY_UPDATE_SAVE);
    }

    private void startGameplayEntityUpdateSave(MinecraftServer server) throws Exception {
        Entity entity = this.entityById(this.savedGameplayEntityId);
        if (entity == null) {
            this.recordFailure("Gameplay saved entity is available for update save");
            this.completePhase(server, Phase.START_EXPLOSION_INTERACTION);
            return;
        }

        BlockPos moved = this.volume.min().offset(9, 1, 8);
        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushPlayerSource(WorldMutationSource.PLAYER, ACTOR, true)) {
            entity.snapTo(moved.getX() + 0.5D, moved.getY(), moved.getZ() + 0.5D, 270.0F, 0.0F);
            entity.setCustomName(Component.literal("lumi-saved-entity-update"));
        }
        this.check(entity.blockPosition().equals(moved), "Gameplay entity saved update moved before save");
        this.gameplayEntityUpdateSaveVersionId = this.nextVersionId(server);
        this.gameplayEntityUpdateSaveValidated = false;
        this.pendingOperation = this.versionService.startSaveVersion(this.level, this.project.name(), "Singleplayer entity update save", ACTOR);
        this.log.info("Queued gameplay entity update save operation " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_GAMEPLAY_ENTITY_UPDATE_SAVE);
    }

    private void checkGameplayEntityUpdateSave(MinecraftServer server) throws Exception {
        if (!this.gameplayEntityUpdateSaveValidated) {
            this.check("Gameplay entity update save created " + this.gameplayEntityUpdateSaveVersionId, () ->
                    this.projectService.loadVersions(server, this.project.name()).stream()
                            .anyMatch(version -> this.gameplayEntityUpdateSaveVersionId.equals(version.id())));
            VersionDiff diff = this.value("Gameplay entity update diff can be built", () ->
                    this.diffService.compareVersionToParent(server, this.project.name(), this.gameplayEntityUpdateSaveVersionId));
            if (diff != null) {
                this.check(diff.changedEntities().stream()
                                .anyMatch(change -> change.entityId().equals(this.savedGameplayEntityId)),
                        "Gameplay entity update save includes the entity");
            }
            this.check("Gameplay entity update save consumed the recovery draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
            this.gameplayEntityUpdateSaveValidated = true;
        }
        this.completePhase(server, Phase.START_EXPLOSION_INTERACTION);
    }

    private void startPrimedTntUndoInteraction(MinecraftServer server) {
        this.primedTntUndoReport = new SingleplayerExplosionRegressionScenario().start(
                this.level,
                this.player,
                this.volume,
                ACTOR,
                this.volume.min().offset(12, 0, 2)
        );
        this.check(this.primedTntUndoReport.placed(), "Player placed primed-undo TNT through gameMode useItemOn");
        this.check(this.primedTntUndoReport.ignited(), "Player ignited primed-undo TNT through gameMode useItemOn");
        this.completePhase(server, Phase.START_PRIMED_TNT_UNDO);
    }

    private void startPrimedTntUndo() throws Exception {
        this.check(this.primedTntUndoReport != null && this.primedTntUndoReport.primedTntPresent(this.level),
                "Primed TNT entity exists before fuse-time undo");
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued primed TNT live undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_PRIMED_TNT_UNDO);
    }

    private void checkPrimedTntUndo() {
        this.check(this.primedTntUndoReport != null
                        && this.primedTntUndoReport.restoredBeforeExplosionUndo(this.level),
                "Fuse-time TNT undo restored TNT block and removed primed entity");
        this.completePhase(this.level.getServer(), Phase.START_POWERED_TNT_UNDO_INTERACTION);
    }

    private void startPoweredTntUndoInteraction(MinecraftServer server) {
        this.poweredTntUndoReport = new SingleplayerExplosionRegressionScenario().startPowered(
                this.level,
                this.player,
                this.volume,
                ACTOR,
                this.volume.min().offset(8, 8, 2)
        );
        this.check(this.poweredTntUndoReport.placed(), "Player placed powered TNT through gameMode useItemOn");
        this.check(this.poweredTntUndoReport.ignited(), "Powered TNT auto-primed through redstone");
        this.completePhase(server, Phase.START_POWERED_TNT_UNDO);
    }

    private void startPoweredTntUndo() throws Exception {
        this.check(this.poweredTntUndoReport != null && this.poweredTntUndoReport.primedTntPresent(this.level),
                "Powered primed TNT entity exists before undo");
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued powered TNT live undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_POWERED_TNT_UNDO);
    }

    private void checkPoweredTntUndo() {
        this.check(this.poweredTntUndoReport != null
                        && this.poweredTntUndoReport.restoredBeforeExplosionUndo(this.level),
                "Powered TNT undo removed the redstone trigger, restored TNT, and removed the primed entity; mismatches="
                        + (this.poweredTntUndoReport == null
                        ? "missing report"
                        : this.poweredTntUndoReport.restorationMismatches(this.level)));
        this.completePhase(this.level.getServer(), Phase.START_CHAINED_TNT_INTERACTION);
    }

    private void startChainedTntInteraction(MinecraftServer server) {
        this.explosionWaitTicks = 0;
        this.chainedTntReport = new SingleplayerExplosionRegressionScenario().startPoweredChain(
                this.level,
                this.player,
                this.volume,
                ACTOR,
                this.volume.min().offset(4, 8, 8)
        );
        this.check(this.chainedTntReport.placed(), "Player placed the TNT chain redstone trigger through gameMode useItemOn");
        this.check(this.chainedTntReport.ignited(), "Pre-existing TNT chain auto-primed through the redstone trigger");
        this.completePhase(server, Phase.CHECK_CHAINED_TNT_CAPTURE);
    }

    private void checkChainedTntCapture(MinecraftServer server) throws Exception {
        if (this.chainedTntReport != null && !this.chainedTntReport.exploded(this.level)) {
            if (++this.explosionWaitTicks < EXPLOSION_WAIT_TIMEOUT_TICKS) {
                return;
            }
        }
        this.check(this.chainedTntReport != null && this.chainedTntReport.exploded(this.level),
                "Redstone TNT chain exploded through the fixture");
        RecoveryDraft draft = this.recoveryService.loadDraft(server, this.project.name()).orElse(null);
        this.check(draft != null && draft.totalChangeCount() > 0,
                "Redstone TNT chain settled into the durable recovery draft");
        this.completePhase(server, Phase.START_CHAINED_TNT_UNDO);
    }

    private void startChainedTntUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued redstone TNT chain live undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_CHAINED_TNT_UNDO);
    }

    private void checkChainedTntUndo() {
        this.check(this.chainedTntReport != null && this.chainedTntReport.restoredAfterUndo(this.level),
                "Redstone TNT chain undo removed the trigger and restored all TNT and witness blocks as one action; mismatches="
                        + (this.chainedTntReport == null
                        ? "missing report"
                        : this.chainedTntReport.restorationMismatches(this.level)));
        this.completePhase(this.level.getServer(), Phase.CREATE_ACTIONLESS_PROJECT);
    }

    private void createActionlessProject(MinecraftServer server) throws Exception {
        BlockPos rollbackPos = this.volume.min().offset(2, 8, 12);
        BlockPos savePos = this.volume.min().offset(12, 8, 12);
        String projectName = "Lumi Actionless Safety " + System.currentTimeMillis();
        this.actionlessProject = this.projectService.createProject(
                this.level,
                projectName,
                rollbackPos,
                savePos,
                ACTOR
        );
        this.check(this.projectService.loadVersions(server, projectName).size() == 1,
                "Actionless safety fixture has an isolated initial HEAD");
        this.completePhase(server, Phase.START_ACTIONLESS_FLUID);
    }

    private void startActionlessFluid(MinecraftServer server) {
        this.actionlessFluidPos = this.volume.min().offset(2, 8, 12);
        this.actionlessWaitTicks = 0;
        this.actionlessUndoCount = UndoRedoHistoryManager.getInstance()
                .recentUndoActions(this.actionlessProject.id().toString(), 100).size();
        this.level.setBlock(this.actionlessFluidPos, Blocks.WATER.defaultBlockState(), 3);
        this.completePhase(server, Phase.CHECK_ACTIONLESS_FLUID);
    }

    private void checkActionlessFluid(MinecraftServer server) throws Exception {
        if (++this.actionlessWaitTicks < 20) {
            return;
        }
        this.check(this.recoveryService.loadDraft(server, this.actionlessProject.name()).isEmpty(),
                "Actionless fluid does not create a player recovery draft");
        this.check(
                UndoRedoHistoryManager.getInstance().recentUndoActions(this.actionlessProject.id().toString(), 100).size()
                        == this.actionlessUndoCount,
                "Actionless fluid does not create a player undo action"
        );
        var dirtyScope = HistoryCaptureManager.getInstance()
                .loadProjectDirtyScope(server, this.actionlessProject.id().toString());
        this.check(dirtyScope.blockSections().contains(ChunkSectionPoint.from(BlockPoint.from(this.actionlessFluidPos))),
                "Actionless fluid marks its durable dirty section");
        this.completePhase(server, Phase.START_ACTIONLESS_ROLLBACK);
    }

    private void startActionlessRollback() throws Exception {
        this.pendingOperation = this.quickRollbackService.quickRollback(this.level, this.actionlessProject.name());
        this.log.info("Queued actionless fluid rollback " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_ACTIONLESS_ROLLBACK);
    }

    private void checkActionlessRollback(MinecraftServer server) throws Exception {
        this.check(this.level.getFluidState(this.actionlessFluidPos).isEmpty(),
                "Rollback to HEAD removes actionless fluid");
        this.check(HistoryCaptureManager.getInstance()
                        .loadProjectDirtyScope(server, this.actionlessProject.id().toString()).isEmpty(),
                "Verified rollback clears the actionless dirty scope");
        this.completePhase(server, Phase.START_ACTIONLESS_SAVE);
    }

    private void startActionlessSave(MinecraftServer server) throws Exception {
        this.actionlessFluidPos = this.volume.min().offset(12, 8, 12);
        this.check(this.level.setBlock(this.actionlessFluidPos, Blocks.WATER.defaultBlockState(), 3),
                "Actionless safety-save fluid changed a fresh block");
        this.actionlessSaveVersionId = this.nextVersionId(server, this.actionlessProject.name());
        this.pendingOperation = this.versionService.startSaveVersion(
                this.level,
                this.actionlessProject.name(),
                "Actionless fluid safety save",
                ACTOR
        );
        this.log.info("Queued actionless fluid safety save " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_ACTIONLESS_SAVE);
    }

    private void checkActionlessSave(MinecraftServer server) throws Exception {
        VersionDiff diff = this.value("Actionless fluid save diff can be built", () ->
                this.diffService.compareVersionToParent(
                        server,
                        this.actionlessProject.name(),
                        this.actionlessSaveVersionId
                ));
        if (diff != null) {
            this.check(diff.changedBlocks().stream()
                            .anyMatch(entry -> entry.pos().equals(BlockPoint.from(this.actionlessFluidPos))),
                    "Save includes the actionless fluid from the dirty scope");
        }
        this.check(this.recoveryService.loadDraft(server, this.actionlessProject.name()).isEmpty(),
                "Actionless safety save still exposes no player draft");
        this.completePhase(server, Phase.START_CREEPER_INCIDENT);
    }

    private void startCreeperIncident(MinecraftServer server) {
        int undoCount = this.undoActionCount();
        this.creeperIncident = new SingleplayerWorldIncidentScenario().startCreeper(this.level, this.volume);
        this.check(this.creeperIncident.changed(this.level),
                "Unowned creeper explosion changed its deterministic witness fixture");
        this.checkWorldIncidentAction("creeper", undoCount);
        this.completePhase(server, Phase.START_CREEPER_INCIDENT_UNDO);
    }

    private void startCreeperIncidentUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued creeper world-incident undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_CREEPER_INCIDENT_UNDO);
    }

    private void checkCreeperIncidentUndo(MinecraftServer server) {
        this.check(this.creeperIncident != null && this.creeperIncident.restored(this.level),
                "One undo restored every creeper world-incident witness block");
        this.completePhase(server, Phase.START_LIGHTNING_INCIDENT);
    }

    private void startLightningIncident(MinecraftServer server) {
        int undoCount = this.undoActionCount();
        this.lightningIncident = new SingleplayerWorldIncidentScenario().startLightning(this.level, this.volume);
        this.check(this.lightningIncident.changed(this.level),
                "Unowned lightning changed weathered copper at its strike position");
        this.checkWorldIncidentAction("lightning", undoCount);
        this.completePhase(server, Phase.START_LIGHTNING_INCIDENT_UNDO);
    }

    private void startLightningIncidentUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued lightning world-incident undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_LIGHTNING_INCIDENT_UNDO);
    }

    private void checkLightningIncidentUndo(MinecraftServer server) {
        this.check(this.lightningIncident != null && this.lightningIncident.restored(this.level),
                "One undo restored the lightning world-incident witness block");
        this.completePhase(server, Phase.START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS);
    }

    private int undoActionCount() {
        return UndoRedoHistoryManager.getInstance()
                .recentUndoActions(this.project.id().toString(), 100)
                .size();
    }

    private void checkWorldIncidentAction(String type, int previousUndoCount) {
        List<UndoRedoAction> actions = UndoRedoHistoryManager.getInstance()
                .recentUndoActions(this.project.id().toString(), 100);
        this.check(actions.size() == previousUndoCount + 1,
                "One " + type + " world incident creates one undo action");
        if (actions.size() <= previousUndoCount) {
            return;
        }
        UndoRedoAction incident = actions.getFirst();
        this.check(("world incident: " + type).equals(incident.actor()),
                "The " + type + " action is attributed to a world incident, not the player");
        this.check(!incident.redoChanges().isEmpty(),
                "The " + type + " world incident owns its supported block consequences");
    }

    private void startExplosionInteraction(MinecraftServer server) throws Exception {
        this.check(this.recoveryService.loadDraft(server, this.project.name()).isEmpty(),
                "First post-save TNT starts with an empty durable draft");
        this.explosionWaitTicks = 0;
        this.explosionReport = new SingleplayerExplosionRegressionScenario().startWithCleanFixture(
                this.level,
                this.player,
                this.volume,
                this.volume.min().offset(13, 8, 13)
        );
        this.check(this.explosionReport.placed(), "Player placed the first TNT after a clean save");
        this.check(this.explosionReport.ignited(), "Player ignited the first TNT after a clean save");
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
        RecoveryDraft draft = this.recoveryService.loadDraft(server, this.project.name()).orElse(null);
        this.check(draft != null && draft.totalChangeCount() > 0,
                "Explosion settled into the durable recovery draft");
        if (draft != null && this.explosionReport != null) {
            this.explosionDestroyedWitnessBlocks = this.explosionReport.destroyedWitnessBlocks(this.level);
            this.check(!this.explosionDestroyedWitnessBlocks.isEmpty(),
                    "Controlled TNT explosion removed at least one witness block");
        }
        this.completePhase(server, Phase.START_EXPLOSION_UNDO);
    }

    private void startExplosionUndo() throws Exception {
        this.pendingOperation = this.undoRedoService.undo(this.level, this.project.name());
        this.log.info("Queued one-action explosion undo " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_EXPLOSION_UNDO);
    }

    private void checkExplosionUndo() {
        this.check(this.explosionReport != null && this.explosionReport.restoredAfterUndo(this.level),
                "One undo restored the first post-save TNT action and every blast witness block");
        this.completePhase(this.level.getServer(), Phase.START_SAVE_DURING_TNT_FUSE);
    }

    private void startSaveDuringTntFuse(MinecraftServer server) throws Exception {
        this.explosionWaitTicks = 0;
        this.saveDuringFuseReport = new SingleplayerExplosionRegressionScenario().startPowered(
                this.level,
                this.player,
                this.volume,
                ACTOR,
                this.volume.min().offset(3, 8, 2)
        );
        this.check(this.saveDuringFuseReport.placed(), "Player placed the save-during-fuse redstone trigger");
        this.check(this.saveDuringFuseReport.ignited(), "Save-during-fuse TNT was primed before save isolation");
        List<UndoRedoAction> actions = UndoRedoHistoryManager.getInstance()
                .recentUndoActions(this.project.id().toString(), 100);
        this.saveDuringFuseActionId = actions.isEmpty() ? "" : actions.getFirst().id();
        this.check(!this.saveDuringFuseActionId.isBlank(), "Save-during-fuse action has a live action id");

        this.saveDuringFuseVersionId = this.nextVersionId(server);
        this.pendingOperation = this.versionService.startSaveVersion(
                this.level,
                this.project.name(),
                "Stable state during TNT fuse",
                ACTOR
        );
        this.log.info("Queued save during TNT fuse " + this.pendingOperation.id());
        this.completePhase(server, Phase.CHECK_SAVE_DURING_TNT_FUSE);
    }

    private void checkSaveDuringTntFuse(MinecraftServer server) throws Exception {
        if (this.saveDuringFuseReport != null && !this.saveDuringFuseReport.exploded(this.level)) {
            if (++this.explosionWaitTicks < EXPLOSION_WAIT_TIMEOUT_TICKS) {
                return;
            }
        }
        this.check(this.saveDuringFuseReport != null && this.saveDuringFuseReport.exploded(this.level),
                "TNT fallout completed after the fuse-time save was isolated");
        List<UndoRedoAction> actions = UndoRedoHistoryManager.getInstance()
                .recentUndoActions(this.project.id().toString(), 100);
        this.check(actions.stream().anyMatch(action -> this.saveDuringFuseActionId.equals(action.id())),
                "Fuse-time save kept explosion fallout on the originating action id");

        RecoveryDraft draft = this.recoveryService.loadDraft(server, this.project.name()).orElse(null);
        this.check(draft != null && this.saveDuringFuseVersionId.equals(draft.baseVersionId()),
                "Post-save TNT fallout draft was rebased to the published fuse-time save");
        Set<BlockPoint> destroyed = this.saveDuringFuseReport == null
                ? Set.of()
                : this.saveDuringFuseReport.destroyedWitnessBlocks(this.level);
        this.check(draft != null && !destroyed.isEmpty() && draft.changes().stream()
                        .anyMatch(change -> destroyed.contains(change.pos())),
                "Rebased draft contains post-save TNT witness damage");
        this.completePhase(server, Phase.START_SAVE_DURING_TNT_ROLLBACK);
    }

    private void startSaveDuringTntRollback() throws Exception {
        this.pendingOperation = this.quickRollbackService.quickRollback(this.level, this.project.name());
        this.log.info("Queued rollback to fuse-time save " + this.pendingOperation.id());
        this.completePhase(this.level.getServer(), Phase.CHECK_SAVE_DURING_TNT_ROLLBACK);
    }

    private void checkSaveDuringTntRollback(MinecraftServer server) throws Exception {
        this.check(this.recoveryService.loadDraft(server, this.project.name()).isEmpty(),
                "Rollback to the fuse-time save cleared only its rebased fallout draft");
        this.completePhase(server, Phase.START_PRIMED_TNT_UNDO_INTERACTION);
    }

    private void startExplosionRedo() throws Exception {
        this.completePhase(this.level.getServer(), Phase.START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS);
    }

    private void checkExplosionRedo() {
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
        this.checkInitialVolumeRestored(this.mode == SingleplayerTestMode.PLAYER_FLOW
                ? "Full restore returned all gameplay blocks to the prepared platform baseline"
                : "Full restore returned all gameplay blocks to initial air");
        if (this.gameplayReport != null) {
            this.check(this.gameplayReport.spawnedEntities().stream()
                            .allMatch(entity -> entity == null || entity.isRemoved()),
                    "Full restore removed gameplay entities");
            this.gameplayReport.cleanup();
            this.gameplayReport = null;
        }
        if (!this.savedGameplayEntityId.isBlank()) {
            Entity entity = this.entityById(this.savedGameplayEntityId);
            this.check(entity == null || entity.isRemoved(),
                    "Full restore removed saved gameplay entity after saved update");
        }
        this.primedTntUndoReport = null;
        this.explosionReport = null;
        this.check("Gameplay restore consumed the recovery draft", () -> this.recoveryService.loadDraft(server, this.project.name()).isEmpty());
        this.check("Final integrity report is valid", () -> this.integrityService.inspect(server, this.project.name()).valid());
        this.completePhase(server, Phase.CHECK_PERFORMANCE);
    }

    private void checkPerformanceBudget(MinecraftServer server) {
        this.recordLoadSample("performance-check");
        for (String line : this.performanceMonitor.summaryLines()) {
            this.log.info(line);
        }
        for (SingleplayerPerformanceMonitor.PerformanceCheck check : this.performanceMonitor.checks()) {
            this.check(check.passed(), check.label() + " (" + check.detail() + ")");
        }
        this.completePhase(server, this.mode == SingleplayerTestMode.LOAD_SMOKE
                ? Phase.CLEANUP
                : Phase.START_LARGE_HISTORY_DIAGNOSTICS);
    }

    private void startLargeHistoryDiagnostics(MinecraftServer server) {
        this.largeHistoryScenario = new SingleplayerLargeHistoryScenario(this.level, this.player.blockPosition(), ACTOR);
        this.completePhase(server, Phase.RUN_LARGE_HISTORY_DIAGNOSTICS);
    }

    private void runLargeHistoryDiagnostics(MinecraftServer server) {
        if (this.largeHistoryScenario == null) {
            this.recordFailure("Large persisted history diagnostics were not initialized");
            this.completePhase(server, Phase.START_BULK_APPLY_DIAGNOSTICS);
            return;
        }

        SingleplayerLargeHistoryScenario.StepResult result = this.largeHistoryScenario.advance(server);
        for (String message : result.messages()) {
            this.message(server, message);
        }
        if (result.operationHandle() != null) {
            this.pendingOperation = result.operationHandle();
            this.log.info("Queued large persisted history operation "
                    + this.pendingOperation.label() + " " + this.pendingOperation.id());
            return;
        }
        if (!result.finished()) {
            return;
        }

        for (SingleplayerLargeHistoryScenario.DiagnosticCheck check : this.largeHistoryScenario.checks()) {
            this.check(check.passed(), check.label() + " (" + check.detail() + ")");
        }
        this.completePhase(server, Phase.START_BULK_APPLY_DIAGNOSTICS);
    }

    private void startBulkApplyDiagnostics(MinecraftServer server) {
        this.bulkApplyDiagnostics = new SingleplayerBulkApplyDiagnostics(
                this.level,
                this.player.blockPosition(),
                this.project.id().toString()
        );
        this.completePhase(server, Phase.RUN_BULK_APPLY_DIAGNOSTICS);
    }

    private void runBulkApplyDiagnostics(MinecraftServer server) {
        if (this.bulkApplyDiagnostics == null) {
            this.recordFailure("Bulk apply diagnostics were not initialized");
            this.completePhase(server, Phase.CLEANUP);
            return;
        }

        SingleplayerBulkApplyDiagnostics.StepResult result = this.bulkApplyDiagnostics.advance(server);
        for (String message : result.messages()) {
            this.message(server, message);
        }
        if (result.operationHandle() != null) {
            this.pendingOperation = result.operationHandle();
            this.log.info("Queued bulk diagnostics operation "
                    + this.pendingOperation.label() + " " + this.pendingOperation.id());
            return;
        }
        if (!result.finished()) {
            return;
        }

        for (SingleplayerBulkApplyDiagnostics.DiagnosticCheck check : this.bulkApplyDiagnostics.checks()) {
            this.check(check.passed(), check.label() + " (" + check.detail() + ")");
        }
        this.completePhase(server, Phase.CLEANUP);
    }

    private void startStructureFixtureTests(MinecraftServer server) {
        this.completePhase(server, Phase.CLEANUP);
    }

    private void runStructureFixtureTests(MinecraftServer server) {
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
        var snapshot = this.worldOperationManager.snapshot(server, this.pendingOperation);
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
        if (this.worldOperationManager.hasActiveOperation(server)) {
            this.worldOperationManager.snapshot(server, this.pendingOperation.projectId())
                    .filter(active -> active.handle() != null)
                    .filter(active -> !this.pendingOperation.id().equals(active.handle().id()))
                    .ifPresent(active -> this.reportOperationProgress(server, active));
            return true;
        }
        if (operation.failed()) {
            this.recordFailure("World operation failed: " + this.pendingOperation.label() + " - " + operation.detail());
        } else {
            this.log.info("Operation completed: " + this.pendingOperation.label() + " " + this.pendingOperation.id());
            if (this.largeHistoryScenario != null) {
                this.largeHistoryScenario.recordCompletion(this.pendingOperation, operation);
            }
            this.worldOperationManager.applyMetrics(this.pendingOperation).ifPresent(metrics -> {
                this.log.info("Operation apply metrics: label=" + this.pendingOperation.label()
                        + ", id=" + this.pendingOperation.id()
                        + ", " + metrics);
                if (this.bulkApplyDiagnostics != null) {
                    this.bulkApplyDiagnostics.recordMetrics(this.pendingOperation, operation, metrics);
                }
                if (this.largeHistoryScenario != null) {
                    this.largeHistoryScenario.recordMetrics(this.pendingOperation, operation, metrics);
                }
            });
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
        this.message(server, "Lumi testing [" + this.phaseStepNumber() + "/" + this.phaseTotalSteps() + "] "
                + this.phase.title() + " - " + this.phase.description());
    }

    private void completePhase(MinecraftServer server, Phase nextPhase) {
        int passed = this.log.passedChecks() - this.phaseStartPasses;
        int failed = this.log.failedChecks() - this.phaseStartFailures;
        String status = failed == 0 ? "passed" : "completed with " + failed + " failure(s)";
        this.message(server, "Lumi testing [" + this.phaseStepNumber() + "/" + this.phaseTotalSteps() + "] "
                + this.phase.title() + " " + status + " (" + passed + " pass, " + failed + " fail)");
        this.recordLoadSample(this.phase.title() + " complete");
        this.phase = nextPhase;
    }

    private int phaseStepNumber() {
        int step = this.phase.stepNumber();
        return this.mode == SingleplayerTestMode.PLAYER_FLOW ? step : step - 1;
    }

    private int phaseTotalSteps() {
        return this.mode == SingleplayerTestMode.PLAYER_FLOW
                ? Phase.totalSteps()
                : Phase.totalSteps() - 1;
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
        this.primedTntUndoReport = null;
        this.explosionReport = null;
        if (this.bulkApplyDiagnostics != null) {
            try {
                this.bulkApplyDiagnostics.cleanup();
            } catch (Exception exception) {
                this.log.fail(Phase.CLEANUP.title(), "Bulk diagnostics cleanup failed", exception);
            }
        }
        if (this.largeHistoryScenario != null) {
            try {
                this.largeHistoryScenario.cleanup();
            } catch (Exception exception) {
                this.log.fail(Phase.CLEANUP.title(), "Large history diagnostics cleanup failed", exception);
            }
        }
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
            for (Entity entity : this.level.getEntities((Entity) null, this.volume.bounds(),
                    entity -> !(entity instanceof ServerPlayer))) {
                entity.discard();
            }
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
        var actual = this.level.getBlockState(pos);
        if (actual.is(block)) {
            this.check(true, label);
            return;
        }
        this.check(false, label + ": expected=" + block + " actual=" + actual);
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

    private ProjectVersion storedVersionById(MinecraftServer server, String versionId) throws Exception {
        return this.versionRepository.load(this.projectService.resolveLayout(server, this.project.name()), versionId).orElse(null);
    }

    private int storedVersionCount(MinecraftServer server) throws Exception {
        return this.storedVersionCount(server, this.project.name());
    }

    private int storedVersionCount(MinecraftServer server, String projectName) throws Exception {
        return this.versionRepository.loadAll(this.projectService.resolveLayout(server, projectName)).size();
    }

    private String nextVersionId(MinecraftServer server) throws Exception {
        return this.nextVersionId(server, this.project.name());
    }

    private String nextVersionId(MinecraftServer server, String projectName) throws Exception {
        return ProjectService.versionId(this.storedVersionCount(server, projectName) + 1);
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

    private void checkInitialVolumeRestored(String label) {
        List<String> mismatches = this.mode == SingleplayerTestMode.PLAYER_FLOW
                ? this.volume.preparedPlayerPlatformMismatches(this.level, 8)
                : this.volume.airMismatches(this.level, 8);
        this.check(mismatches.isEmpty(), mismatches.isEmpty()
                ? label
                : label + ": mismatches=" + String.join("; ", mismatches));
    }

    private String chunkGeneratorName() {
        Object generator = this.level.getChunkSource().getGenerator();
        return generator == null ? "unknown" : generator.getClass().getName();
    }

    private Entity gameplayEntity() {
        if (this.gameplayReport == null) {
            return null;
        }
        for (Entity entity : this.gameplayReport.spawnedEntities()) {
            if (entity != null
                    && !entity.isRemoved()
                    && entity.hasCustomName()
                    && "lumi-runtime-entity".equals(entity.getCustomName().getString())) {
                return entity;
            }
        }
        return null;
    }

    private String stateProperty(io.github.luma.domain.model.StoredBlockChange change, String propertyName) {
        if (change == null || change.newValue() == null || change.newValue().stateTag() == null) {
            return "";
        }
        CompoundTag properties = change.newValue().stateTag().getCompound("Properties").orElseGet(CompoundTag::new);
        return properties.getString(propertyName).orElse("");
    }

    private Entity entityById(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return null;
        }
        try {
            return this.level.getEntity(UUID.fromString(entityId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private void recordLoadSample(String label) {
        if (this.mode == SingleplayerTestMode.LOAD_SMOKE) {
            this.performanceMonitor.recordLoadSample(label);
        }
    }

    private void recordFirstInteraction(String label, long startedAtNanos, long startedCpuNanos) {
        if (this.mode != SingleplayerTestMode.LOAD_SMOKE) {
            return;
        }
        long cpuNanos = -1L;
        long finishedCpuNanos = SingleplayerPerformanceMonitor.currentThreadCpuNanos();
        if (startedCpuNanos >= 0L && finishedCpuNanos >= startedCpuNanos) {
            cpuNanos = finishedCpuNanos - startedCpuNanos;
        }
        this.performanceMonitor.recordFirstInteraction(label, System.nanoTime() - startedAtNanos, cpuNanos);
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
        PREPARE_PLAYER_AREA(
                "Player-flow area setup",
                "find normal-world terrain, prepare a build platform, and stand the player on it"
        ),
        CREATE_PROJECT("Project setup", "create the temporary project and initial snapshot"),
        CHECK_BOOTSTRAP_STORAGE(
                "Bootstrap storage smoke",
                "verify world-origin, pre-mod backup, snapshot refs, and storage migration bootstrap"
        ),
        CAPTURE_DRAFT("Capture and pending diff", "record builder edits and inspect pending history"),
        START_UNDO("Queue live undo", "start undo through the operation model"),
        CHECK_UNDO("Verify live undo", "check the world after undo completes"),
        START_REDO("Queue live redo", "start redo through the operation model"),
        CHECK_REDO("Verify live redo", "check the world after redo completes"),
        START_SAVE("Queue save", "save the pending tracked work"),
        CHECK_SAVE("Verify save", "inspect version, patch, draft isolation, and cleanup dry-run"),
        START_AMEND("Queue amend", "replace the active branch head with merged tracked work"),
        CHECK_AMEND("Verify amend", "inspect amended history and world state"),
        CHECK_METADATA_AND_SETTINGS("Metadata and settings smoke", "rename, tag, and persist every project setting"),
        START_BRANCH_SAVE("Branch and save", "create a branch, switch to it, and save divergent work"),
        CHECK_BRANCH_SAVE("Verify branch and export", "compare branch history and export packages"),
        START_MERGE_TARGET_SWITCH("Queue merge target switch", "switch back to main before local merge"),
        CHECK_MERGE_TARGET_SWITCH("Verify merge target switch", "check the main branch world before merge"),
        START_MERGE_SMOKE("Queue local merge", "merge source branch changes into the active main branch"),
        CHECK_MERGE_SMOKE("Verify local merge", "check merge version, branch heads, and applied blocks"),
        START_PARTIAL_RESTORE("Queue partial restore", "plan and start a selected-area restore"),
        CHECK_PARTIAL_RESTORE("Verify partial restore", "check selected-area restore output"),
        START_RESTORE_INITIAL("Queue full restore", "plan and start restore to the initial version"),
        CHECK_RESTORE_INITIAL("Verify full restore", "check final world state and project integrity"),
        START_WORK_ZONE_SMOKE("Work-zone smoke", "create a zone, make deterministic random edits, and save zone history"),
        CHECK_WORK_ZONE_SMOKE("Verify work-zone smoke", "inspect zone metadata, zone history, and saved diff output"),
        START_WORK_ZONE_AMEND("Work-zone amend", "amend the active zone head with a selected zone edit"),
        CHECK_WORK_ZONE_AMEND("Verify work-zone amend", "check zone amend head replacement and zone history visibility"),
        START_CONCURRENT_SAVE_SMOKE("Concurrent save smoke", "start one save and reject an overlapping second save"),
        CHECK_CONCURRENT_SAVE_SMOKE("Verify concurrent save smoke", "check only the first save committed"),
        START_EXTERNAL_TOOL_STRESS(
                "External-tool source diagnostics",
                "record WorldEdit and Axiom-sourced edits through the external capture path"
        ),
        CHECK_EXTERNAL_TOOL_STRESS(
                "Verify external-tool source diagnostics",
                "save and inspect the external-tool captured edits"
        ),
        CHECK_PLAYER_INTERACTIONS("Gameplay interactions", "exercise broad block, stateful block, fluid, entity, and water-bridge actions"),
        START_GAMEPLAY_UNDO("Queue gameplay undo", "undo the latest gameplay action through the operation model"),
        CHECK_GAMEPLAY_UNDO("Verify gameplay undo", "check that the latest gameplay action was reverted"),
        START_GAMEPLAY_REDO("Queue gameplay redo", "redo the latest gameplay action through the operation model"),
        CHECK_GAMEPLAY_REDO("Verify gameplay redo", "check that the latest gameplay action returned"),
        START_GAMEPLAY_SAVE("Queue gameplay save", "save the player-built gameplay draft"),
        CHECK_GAMEPLAY_SAVE("Verify gameplay save", "check gameplay save output and preview fulfillment"),
        START_ENTITY_QUICK_ROLLBACK("Queue entity quick rollback", "mutate a saved entity and restore the active head"),
        CHECK_ENTITY_QUICK_ROLLBACK("Verify entity quick rollback", "check saved entity state after quick rollback"),
        START_GAMEPLAY_ENTITY_UPDATE_SAVE("Queue entity update save", "save an update to an existing gameplay entity"),
        CHECK_GAMEPLAY_ENTITY_UPDATE_SAVE("Verify entity update save", "check saved entity history before full restore"),
        START_PRIMED_TNT_UNDO_INTERACTION("Primed TNT interaction", "place and ignite TNT before its fuse completes"),
        START_PRIMED_TNT_UNDO("Queue primed TNT undo", "undo TNT ignition before the primed entity explodes"),
        CHECK_PRIMED_TNT_UNDO("Verify primed TNT undo", "check that undo removed the primed entity and restored the block"),
        START_POWERED_TNT_UNDO_INTERACTION("Powered TNT interaction", "place redstone beside existing TNT before its fuse completes"),
        START_POWERED_TNT_UNDO("Queue powered TNT undo", "undo the redstone trigger before the primed TNT explodes"),
        CHECK_POWERED_TNT_UNDO("Verify powered TNT undo", "check that redstone is removed and restored TNT remains inert"),
        START_CHAINED_TNT_INTERACTION("Redstone TNT chain", "place redstone beside ten existing TNT blocks and let the chain explode"),
        CHECK_CHAINED_TNT_CAPTURE("Verify TNT chain capture", "check that one live undo action owns the full chain"),
        START_CHAINED_TNT_UNDO("Queue TNT chain undo", "undo the redstone TNT chain through the operation model"),
        CHECK_CHAINED_TNT_UNDO("Verify TNT chain undo", "check that persistent TNT chain blocks and witnesses were restored"),
        CREATE_ACTIONLESS_PROJECT("Actionless safety fixture", "create an isolated project HEAD for ambient mutation checks"),
        START_ACTIONLESS_FLUID("Actionless fluid", "mutate fluid without a player action and wait for ambient fallout"),
        CHECK_ACTIONLESS_FLUID("Verify actionless safety capture", "check dirty scope without player draft or undo"),
        START_ACTIONLESS_ROLLBACK("Queue actionless rollback", "restore actionless fluid to the active head"),
        CHECK_ACTIONLESS_ROLLBACK("Verify actionless rollback", "check the authoritative head removed ambient fluid"),
        START_ACTIONLESS_SAVE("Queue actionless save", "save ambient fluid from dirty safety history"),
        CHECK_ACTIONLESS_SAVE("Verify actionless save", "check the published patch includes ambient fluid"),
        START_CREEPER_INCIDENT("Creeper world incident", "explode an unowned creeper against deterministic witnesses"),
        START_CREEPER_INCIDENT_UNDO("Queue creeper incident undo", "undo the project-owned creeper incident"),
        CHECK_CREEPER_INCIDENT_UNDO("Verify creeper incident undo", "check one incident action restored its witnesses"),
        START_LIGHTNING_INCIDENT("Lightning world incident", "strike deterministic weathered copper without a player action"),
        START_LIGHTNING_INCIDENT_UNDO("Queue lightning incident undo", "undo the project-owned lightning incident"),
        CHECK_LIGHTNING_INCIDENT_UNDO("Verify lightning incident undo", "check one incident action restored the strike state"),
        START_EXPLOSION_INTERACTION("TNT interaction", "place and ignite TNT through player game-mode actions"),
        CHECK_EXPLOSION_CAPTURE("Verify TNT capture", "wait for the controlled explosion and inspect its draft"),
        START_EXPLOSION_UNDO("Queue TNT undo", "undo the controlled explosion through the operation model"),
        CHECK_EXPLOSION_UNDO("Verify TNT undo", "check that the controlled explosion was restored"),
        START_SAVE_DURING_TNT_FUSE("Save during TNT fuse", "publish a stable state while powered TNT is primed"),
        CHECK_SAVE_DURING_TNT_FUSE("Verify fuse-time save", "check action ownership and post-save draft rebase"),
        START_SAVE_DURING_TNT_ROLLBACK("Queue fuse-time rollback", "return post-save fallout to the fuse-time save"),
        CHECK_SAVE_DURING_TNT_ROLLBACK("Verify fuse-time rollback", "check the rebased fallout draft was consumed"),
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
        START_LARGE_HISTORY_DIAGNOSTICS(
                "Large history diagnostics",
                "prepare a large persisted save, branch, and restore scenario"
        ),
        RUN_LARGE_HISTORY_DIAGNOSTICS(
                "Run large history diagnostics",
                "save and restore large stored project history through real services"
        ),
        START_BULK_APPLY_DIAGNOSTICS("Bulk apply diagnostics", "prepare large dense, fallback, and sparse apply scenarios"),
        RUN_BULK_APPLY_DIAGNOSTICS("Run bulk apply diagnostics", "apply and delete large prepared block batches with metrics"),
        START_STRUCTURE_FIXTURE_TESTS(
                "Structure fixture setup",
                "prepare saved redstone fixture structures for control-by-control undo checks"
        ),
        RUN_STRUCTURE_FIXTURE_TESTS(
                "Structure fixture undo checks",
                "press every saved fixture button and lever, then verify live undo restores blocks, inventories, and entities"
        ),
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
                case PREPARE_PLAYER_AREA, CREATE_PROJECT, CHECK_BOOTSTRAP_STORAGE, CAPTURE_DRAFT -> CLEANUP;
                case START_UNDO, CHECK_UNDO, START_REDO, CHECK_REDO -> START_SAVE;
                case START_SAVE, CHECK_SAVE -> CLEANUP;
                case START_AMEND, CHECK_AMEND -> START_BRANCH_SAVE;
                case CHECK_METADATA_AND_SETTINGS -> START_BRANCH_SAVE;
                case START_BRANCH_SAVE -> START_RESTORE_INITIAL;
                case CHECK_BRANCH_SAVE -> START_PARTIAL_RESTORE;
                case START_PARTIAL_RESTORE, CHECK_PARTIAL_RESTORE -> START_RESTORE_INITIAL;
                case START_RESTORE_INITIAL, CHECK_RESTORE_INITIAL,
                     START_WORK_ZONE_SMOKE, CHECK_WORK_ZONE_SMOKE,
                     START_WORK_ZONE_AMEND, CHECK_WORK_ZONE_AMEND,
                     START_CONCURRENT_SAVE_SMOKE, CHECK_CONCURRENT_SAVE_SMOKE,
                     START_MERGE_TARGET_SWITCH, CHECK_MERGE_TARGET_SWITCH,
                     START_MERGE_SMOKE, CHECK_MERGE_SMOKE,
                     START_EXTERNAL_TOOL_STRESS, CHECK_EXTERNAL_TOOL_STRESS,
                     CHECK_PLAYER_INTERACTIONS,
                     START_GAMEPLAY_UNDO, CHECK_GAMEPLAY_UNDO, START_GAMEPLAY_REDO, CHECK_GAMEPLAY_REDO,
                     START_GAMEPLAY_SAVE, CHECK_GAMEPLAY_SAVE,
                     START_ENTITY_QUICK_ROLLBACK, CHECK_ENTITY_QUICK_ROLLBACK,
                     START_GAMEPLAY_ENTITY_UPDATE_SAVE, CHECK_GAMEPLAY_ENTITY_UPDATE_SAVE,
                     START_PRIMED_TNT_UNDO_INTERACTION, START_PRIMED_TNT_UNDO, CHECK_PRIMED_TNT_UNDO,
                     START_POWERED_TNT_UNDO_INTERACTION, START_POWERED_TNT_UNDO, CHECK_POWERED_TNT_UNDO,
                     START_CHAINED_TNT_INTERACTION, CHECK_CHAINED_TNT_CAPTURE,
                     START_CHAINED_TNT_UNDO, CHECK_CHAINED_TNT_UNDO,
                     CREATE_ACTIONLESS_PROJECT,
                     START_ACTIONLESS_FLUID, CHECK_ACTIONLESS_FLUID,
                     START_ACTIONLESS_ROLLBACK, CHECK_ACTIONLESS_ROLLBACK,
                     START_ACTIONLESS_SAVE, CHECK_ACTIONLESS_SAVE,
                     START_CREEPER_INCIDENT, START_CREEPER_INCIDENT_UNDO, CHECK_CREEPER_INCIDENT_UNDO,
                     START_LIGHTNING_INCIDENT, START_LIGHTNING_INCIDENT_UNDO, CHECK_LIGHTNING_INCIDENT_UNDO,
                     START_EXPLOSION_INTERACTION,
                     CHECK_EXPLOSION_CAPTURE, START_EXPLOSION_UNDO, CHECK_EXPLOSION_UNDO,
                     START_SAVE_DURING_TNT_FUSE, CHECK_SAVE_DURING_TNT_FUSE,
                     START_SAVE_DURING_TNT_ROLLBACK, CHECK_SAVE_DURING_TNT_ROLLBACK,
                     START_EXPLOSION_REDO, CHECK_EXPLOSION_REDO,
                     START_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS, CHECK_RESTORE_INITIAL_AFTER_PLAYER_INTERACTIONS,
                     CHECK_PERFORMANCE, START_LARGE_HISTORY_DIAGNOSTICS, RUN_LARGE_HISTORY_DIAGNOSTICS,
                     START_BULK_APPLY_DIAGNOSTICS, RUN_BULK_APPLY_DIAGNOSTICS,
                     START_STRUCTURE_FIXTURE_TESTS, RUN_STRUCTURE_FIXTURE_TESTS,
                     CLEANUP -> CLEANUP;
            };
        }
    }
}
