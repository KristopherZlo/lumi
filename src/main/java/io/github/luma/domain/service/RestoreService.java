package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.RestorePlanSummary;
import io.github.luma.domain.model.RestoreReturnPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.EntityApplyMode;
import io.github.luma.minecraft.debug.PartialRestoreDiagnosticsLog;
import io.github.luma.minecraft.world.MechanismReplayScope;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.PreparedWorldChangeBatches;
import io.github.luma.minecraft.world.SnapshotBatchPreparer;
import io.github.luma.minecraft.world.WorldChangeBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import io.github.luma.storage.repository.WorldOriginRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Restores a project to a target version through checkpoint snapshots and patch
 * replay.
 *
 * <p>The service builds a restore plan off-thread, decodes the required
 * snapshot, patch, and baseline payloads into prepared chunk batches, and hands
 * those batches to {@link WorldOperationManager} for bounded tick-thread
 * application.
 */
public final class RestoreService {

    private static final int MAX_COLLAPSE_PLACEMENTS = 1_000_000;
    private static final int MAX_COLLAPSE_NATIVE_SECTIONS = 2_048;

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final RestoreChunkCollector chunkCollector = new RestoreChunkCollector(this.patchMetaRepository);
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final WorldOriginRepository worldOriginRepository = new WorldOriginRepository();
    private final VersionService versionService = new VersionService();
    private final PartialRestorePlanner partialRestorePlanner = new PartialRestorePlanner();
    private final PartialRestoreTargetStatePlanner partialRestoreTargetStatePlanner = new PartialRestoreTargetStatePlanner();
    private final PartialRestorePendingDraftProvider partialRestorePendingDraftProvider =
            new PartialRestorePendingDraftProvider();
    private final RestoreCompletionCoordinator completionCoordinator = new RestoreCompletionCoordinator();
    private final SnapshotBatchPreparer snapshotBatchPreparer = new SnapshotBatchPreparer();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final PreparedChunkBatchCollapser batchCollapser = new PreparedChunkBatchCollapser();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final DirectRestorePatchPlanner directRestorePatchPlanner = new DirectRestorePatchPlanner();
    private final RestoreRequestResolver requestResolver = new RestoreRequestResolver();
    private final RestorePlanBuilder restorePlanBuilder = new RestorePlanBuilder();
    private final RestorePayloadLoader payloadLoader = new RestorePayloadLoader();
    private final RestoreEntityStateResolver entityStateResolver = new RestoreEntityStateResolver(
            this.chunkCollector,
            this.baselineChunkRepository,
            this.snapshotReader,
            this.payloadLoader,
            this.restorePlanBuilder,
            this.batchCollapser
    );
    private final WorldRootRestoreBaselineScope worldRootBaselineScope = new WorldRootRestoreBaselineScope(
            this.restorePlanBuilder,
            this.chunkCollector
    );
    private final PartialRestoreDiagnosticsLog partialRestoreDiagnosticsLog = new PartialRestoreDiagnosticsLog();
    private final BlockTargetStateResolver blockTargetStateResolver = new BlockTargetStateResolver();
    private final RestoreMechanismReconciliationPlanner mechanismReconciliationPlanner =
            new RestoreMechanismReconciliationPlanner();
    private final ExactRootStateRestoreDecoder exactRootStateRestoreDecoder = new ExactRootStateRestoreDecoder(
            this.baselineChunkRepository,
            this.snapshotReader,
            this.chunkCollector,
            this.snapshotBatchPreparer
    );
    private final ExactRootStateRestorePlanner exactRootStateRestorePlanner = new ExactRootStateRestorePlanner(
            this.baselineChunkRepository,
            this.chunkCollector
    );
    private final RootLikeRestoreChunkResolver rootLikeRestoreChunkResolver = new RootLikeRestoreChunkResolver(
            this.snapshotReader,
            this.baselineChunkRepository
    );
    private final RestoreUndoActionFactory restoreUndoActionFactory = new RestoreUndoActionFactory();

    /**
     * Starts a restore operation for the given project and target version.
     *
     * <p>If configured, the current pending draft is first saved as a restore
     * checkpoint so the player can return to the pre-restore state.
     */
    public OperationHandle restore(ServerLevel level, String projectName, String versionId) throws IOException {
        return this.restore(level, projectName, versionId, "", false);
    }

    public OperationHandle restore(
            ServerLevel level,
            String projectName,
            String versionId,
            boolean trustedImportedPackage
    ) throws IOException {
        return this.restore(level, projectName, versionId, "", trustedImportedPackage);
    }

    /**
     * Restores the world to a branch head while keeping that branch as the
     * completion target even when the head version originally belongs to another
     * branch line.
     */
    public OperationHandle restoreVariantHead(ServerLevel level, String projectName, String targetVariantId) throws IOException {
        return this.restoreVariantHead(level, projectName, targetVariantId, false);
    }

    public OperationHandle restoreVariantHeadUndoable(ServerLevel level, String projectName, String targetVariantId) throws IOException {
        return this.restoreVariantHead(level, projectName, targetVariantId, true);
    }

    private OperationHandle restoreVariantHead(
            ServerLevel level,
            String projectName,
            String targetVariantId,
            boolean recordUndoRedoAction
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant targetVariant = variants.stream()
                .filter(variant -> variant.id().equals(targetVariantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + targetVariantId));
        if (targetVariant.headVersionId() == null || targetVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Variant head version is missing: " + targetVariantId);
        }
        return this.restore(level, projectName, targetVariant.headVersionId(), targetVariant.id(), false, recordUndoRedoAction);
    }

    public OperationHandle restoreToVariant(
            ServerLevel level,
            String projectName,
            String versionId,
            String targetVariantId
    ) throws IOException {
        return this.restore(level, projectName, versionId, targetVariantId, false);
    }

    private OperationHandle restore(
            ServerLevel level,
            String projectName,
            String versionId,
            String targetVariantId,
            boolean trustedImportedPackage
    ) throws IOException {
        return this.restore(level, projectName, versionId, targetVariantId, trustedImportedPackage, false);
    }

    private OperationHandle restore(
            ServerLevel level,
            String projectName,
            String versionId,
            String targetVariantId,
            boolean trustedImportedPackage,
            boolean recordUndoRedoAction
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "restore-version",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> this.prepareRestoreOperation(
                        level,
                        layout,
                        project,
                        versionId,
                        targetVariantId,
                        trustedImportedPackage,
                        recordUndoRedoAction,
                        progressSink
                )
        );
    }

    private WorldOperationManager.PreparedApplyOperation prepareRestoreOperation(
            ServerLevel level,
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            String versionId,
            String targetVariantId,
            boolean trustedImportedPackage,
            boolean recordUndoRedoAction,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        progressSink.update(OperationStage.PREPARING, 0, 0, "Preparing restore request");
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion version = this.requestResolver.resolveVersion(project, versions, variants, versionId);
        ProjectVariant targetVariant = this.requestResolver.restoreTargetVariant(variants, version, targetVariantId);
        ProjectVariant activeVariant = this.requestResolver.activeVariant(project, variants);
        String activeHeadVersionId = activeVariant.headVersionId();
        this.requestResolver.requireTrustedImportedRestore(level, layout, project, versions, trustedImportedPackage);

        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> frozenSession = HistoryCaptureManager.getInstance()
                .freezeWorkingDraft(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> frozenDraft = frozenSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft pendingDraft = frozenDraft
                .or(() -> persistedDraft)
                .orElse(null);
        LumaMod.LOGGER.info(
                "Starting restore request for project {} to version {} on variant {}",
                project.name(),
                version.id(),
                targetVariant.id()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Starting restore for project {} to version {} on variant {} using pendingDraft={} from {}",
                project.name(),
                version.id(),
                targetVariant.id(),
                pendingDraft != null && !pendingDraft.isEmpty(),
                frozenDraft.isPresent() ? "frozen live buffer" : (persistedDraft.isPresent() ? "persisted draft" : "none")
        );

        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "restore-started",
                "Started restore to version " + version.id(),
                version.id(),
                targetVariant.id()
        ));

        String returnVersionId = activeHeadVersionId;
        if (pendingDraft != null && !pendingDraft.isEmpty()) {
            LumaMod.LOGGER.info(
                    "Creating safety checkpoint before restore for project {} with {} pending changes",
                    project.name(),
                    pendingDraft.totalChangeCount()
            );
            LumaDebugLog.log(
                    project,
                    "restore",
                    "Writing safety checkpoint before restore for project {} with {} draft changes",
                    project.name(),
                    pendingDraft.totalChangeCount()
            );
            progressSink.update(OperationStage.WRITING, 0, pendingDraft.totalChangeCount(), "Writing restore checkpoint");
            ProjectVersion checkpoint = this.versionService.writeVersion(
                    level,
                    layout,
                    project,
                    pendingDraft,
                    "",
                    "Lumi",
                    VersionKind.RESTORE,
                    false,
                    progressSink
            );
            returnVersionId = checkpoint.id();
        }
        if (returnVersionId != null && !returnVersionId.isBlank()) {
            RestoreReturnPoint point = new RestoreReturnPoint(
                    project.id().toString(),
                    activeVariant.id(),
                    returnVersionId,
                    Instant.now(),
                    version.id()
            );
            this.recoveryRepository.saveRestoreReturnPoint(layout, point);
            this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                    point.createdAt(),
                    "restore-return-point-saved",
                    "Saved return point before restore",
                    point.versionId(),
                    point.variantId()
            ));
        }
        RestoreUndoAction restoreUndoAction = recordUndoRedoAction
                ? this.restoreUndoActionFactory.quickRollbackUndoAction(
                        project.id().toString(),
                        level.dimension().identifier().toString(),
                        version.id(),
                        pendingDraft
                )
                : null;

        Optional<List<PreparedChunkBatch>> prepared = this.tryDecodeDirectRestore(
                layout,
                project,
                versions,
                variants,
                version,
                pendingDraft,
                level,
                progressSink
        );

        List<PreparedChunkBatch> batches = prepared.orElseGet(() -> {
            try {
                if (version.versionKind() == VersionKind.WORLD_ROOT) {
                    return this.decodeWorldRootRestore(layout, project, level, progressSink);
                }
                progressSink.update(OperationStage.PREPARING, 0, 1, "Planning restore");
                RestorePlan plan = this.restorePlanBuilder.build(
                        layout,
                        project,
                        versions,
                        version,
                        this.chunkCollector.mergeChunks(
                                this.worldRootFallbackBaselineScope(layout, project, versions, variants, version),
                                this.chunkCollector.touchedChunksForDraft(pendingDraft)
                        )
                );
                LumaMod.LOGGER.info(
                        "Restore plan for project {} uses anchor={}, patches={}, baselineGaps={}",
                        project.name(),
                        plan.anchor().id(),
                        plan.patchChain().size(),
                        plan.baselineGaps().size()
                );
                return this.decodePlan(layout, level, plan, progressSink);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        });
        List<PreparedChunkBatch> finalBatches =
                this.withAuthoritativeEntityReplacementBatches(layout, versions, version.id(), batches);
        return new WorldOperationManager.PreparedApplyOperation(
                finalBatches,
                () -> this.completionCoordinator.completeRestore(
                        level,
                        layout,
                        project,
                        variants,
                        targetVariant,
                        version,
                        finalBatches.size(),
                        restoreUndoAction
                )
        );
    }

    public RestorePlanSummary summarizeRestorePlan(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.requestResolver.resolveVersion(project, versions, variants, versionId);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        String baseVersionId = activeVariant == null ? "" : activeVariant.headVersionId();
        List<ChunkPoint> pendingChunks = HistoryCaptureManager.getInstance()
                .snapshotDraft(level.getServer(), project.id().toString())
                .map(this.chunkCollector::touchedChunksForDraft)
                .orElse(List.of());

        if (targetVersion.id().equals(baseVersionId)) {
            List<ChunkPoint> sameTargetChunks = pendingChunks;
            return new RestorePlanSummary(
                    sameTargetChunks.isEmpty() ? RestorePlanMode.NO_OP : RestorePlanMode.PATCH_REPLAY,
                    sameTargetChunks,
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        DirectRestorePatchPlan directPlan = this.directRestorePatchPlanner.applicablePlan(project, versions, variants, targetVersion);
        if (directPlan != null) {
            return new RestorePlanSummary(
                    RestorePlanMode.PATCH_REPLAY,
                    this.chunkCollector.mergeChunks(
                            this.chunkCollector.touchedChunksForVersions(layout, directPlan.allVersions()),
                            pendingChunks
                    ),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT || targetVersion.versionKind() == VersionKind.INITIAL) {
            return new RestorePlanSummary(
                    this.worldRootFallbackMode(level, project),
                    this.chunkCollector.mergeChunks(this.rootLikeRestoreChunkResolver.resolve(layout, targetVersion), pendingChunks),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        RestorePlan plan = this.restorePlanBuilder.build(
                layout,
                project,
                versions,
                targetVersion,
                this.worldRootFallbackBaselineScope(layout, project, versions, variants, targetVersion)
        );
        return new RestorePlanSummary(
                RestorePlanMode.BASELINE_CHUNKS,
                this.chunkCollector.mergeChunks(this.touchedChunksForPlan(plan), pendingChunks),
                targetVersion.variantId(),
                baseVersionId,
                targetVersion.id()
        );
    }

    public OperationHandle partialRestore(ServerLevel level, PartialRestoreRequest request) throws IOException {
        if (request == null || request.bounds() == null) {
            throw new IllegalArgumentException("Partial restore requires bounds");
        }

        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), request.projectName());
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + request.projectName()));
        this.partialRestoreDiagnosticsLog.logSelectedArea(level, project, request);

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "partial-restore",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> this.preparePartialRestoreOperation(level, layout, project, request, progressSink)
        );
    }

    private WorldOperationManager.PreparedApplyOperation preparePartialRestoreOperation(
            ServerLevel level,
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            PartialRestoreRequest request,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        progressSink.update(OperationStage.PREPARING, 0, 0, "Preparing partial restore request");
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.requestResolver.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.requestResolver.activeVariant(project, variants);
        RecoveryDraft pendingDraft = this.partialRestorePendingDraftProvider.freeze(level, layout, project.id().toString())
                .orElse(null);

        LumaMod.LOGGER.info(
                "Starting partial restore for project {} to version {} over {}",
                project.name(),
                targetVersion.id(),
                request.bounds()
        );
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "partial-restore-started",
                "Started partial restore to version " + targetVersion.id(),
                targetVersion.id(),
                activeVariant.id()
        ));

        PartialRestoreDraft partialDraft = this.buildPartialRestoreDraft(
                layout,
                project,
                versions,
                variants,
                activeVariant,
                targetVersion,
                pendingDraft,
                request,
                level.getMinY(),
                level.getMaxY(),
                progressSink
        );
        this.partialRestoreDiagnosticsLog.logPlannedDraft(
                project,
                activeVariant,
                targetVersion,
                request,
                partialDraft.mode(),
                partialDraft.draft()
        );
        if (partialDraft.draft().isEmpty()) {
            throw new IllegalArgumentException(request.restoreMode() == PartialRestoreMode.OUTSIDE_SELECTED_AREA
                    ? "Partial restore has no changes outside the selected region"
                    : "Partial restore has no changes inside the selected region");
        }
        List<PreparedChunkBatch> decodedBatches = this.decodeStoredChanges(
                level,
                partialDraft.draft().changes(),
                partialDraft.draft().entityChanges(),
                true
        );
        List<PreparedChunkBatch> batches;
        try (var ignored = LumaLoadLog.measure(
                "restore",
                "PreparedChunkBatchCollapser.collapse",
                "source=partial-restore, batches=" + decodedBatches.size()
        )) {
            batches = this.batchCollapser.collapse(decodedBatches);
        }
        boolean partialRestoreDiagnostics = this.partialRestoreDiagnosticsLog.enabled(request);
        return new WorldOperationManager.PreparedApplyOperation(
                batches,
                () -> {
                    if (partialRestoreDiagnostics) {
                        this.partialRestoreDiagnosticsLog.logPostApplyRemaining(
                                level,
                                project,
                                request,
                                partialDraft.mode(),
                                partialDraft.draft()
                        );
                    }
                    this.completionCoordinator.completePartialRestore(
                            level,
                            layout,
                            project,
                            pendingDraft,
                            request,
                            partialDraft.draft(),
                            batches.size()
                    );
                },
                partialRestoreDiagnostics
        );
    }

    public PartialRestorePlanSummary summarizePartialRestorePlan(ServerLevel level, PartialRestoreRequest request) throws IOException {
        if (request == null || request.bounds() == null) {
            throw new IllegalArgumentException("Partial restore requires bounds");
        }

        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), request.projectName());
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + request.projectName()));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.requestResolver.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.requestResolver.activeVariant(project, variants);
        Optional<RecoveryDraft> pendingDraft = this.partialRestorePendingDraftProvider.snapshot(
                level,
                layout,
                project.id().toString()
        );

        PartialRestoreDraft draft = this.buildPartialRestoreDraft(
                layout,
                project,
                versions,
                variants,
                activeVariant,
                targetVersion,
                pendingDraft.orElse(null),
                request,
                level.getMinY(),
                level.getMaxY(),
                (stage, completed, total, detail) -> {
                }
        );

        return new PartialRestorePlanSummary(
                draft.draft().isEmpty() ? RestorePlanMode.NO_OP : draft.mode(),
                request.bounds(),
                request.restoreMode(),
                request.regionSource(),
                ChunkSelectionFactory.fromStoredChanges(draft.draft().changes()),
                activeVariant.id(),
                activeVariant.headVersionId(),
                targetVersion.id(),
                draft.draft().changes().size(),
                draft.draft().entityChanges().size()
        );
    }

    private PartialRestoreDraft buildPartialRestoreDraft(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            int worldMinY,
            int worldMaxY,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        DirectRestorePatchPlan directPlan = this.directRestorePatchPlanner.applicablePlan(project, versions, variants, targetVersion);
        if (directPlan == null) {
            return this.buildTargetStatePartialRestoreDraft(
                    layout,
                    project,
                    versions,
                    activeVariant,
                    targetVersion,
                    pendingDraft,
                    request,
                    worldMinY,
                    worldMaxY,
                    progressSink
            );
        }

        List<ChunkPoint> selectedChunks = request.restoreMode() == PartialRestoreMode.OUTSIDE_SELECTED_AREA
                ? null
                : this.chunkCollector.chunksIntersecting(request.bounds());
        PatchWorldChanges reverseChanges = this.payloadLoader.loadVersionWorldChanges(
                layout,
                directPlan.reverseVersions(),
                selectedChunks
        );
        PatchWorldChanges forwardChanges = this.payloadLoader.loadVersionWorldChanges(
                layout,
                directPlan.forwardVersions(),
                selectedChunks
        );
        if (this.mechanismReconciliationPlanner.containsMechanismState(pendingDraft == null ? List.of() : pendingDraft.changes())
                || this.mechanismReconciliationPlanner.containsMechanismState(reverseChanges.blockChanges())
                || this.mechanismReconciliationPlanner.containsMechanismState(forwardChanges.blockChanges())) {
            LumaDebugLog.log(
                    project,
                    "restore",
                    "Partial restore for project {} target {} switched to target-state planning because direct path contains mechanism state",
                    project.name(),
                    targetVersion.id()
            );
            try {
                return this.buildTargetStatePartialRestoreDraft(
                        layout,
                        project,
                        versions,
                        activeVariant,
                        targetVersion,
                        pendingDraft,
                        request,
                        worldMinY,
                        worldMaxY,
                        progressSink
                );
            } catch (PartialRestoreTargetStateUnavailableException exception) {
                LumaMod.LOGGER.info(
                        "Partial restore for project {} to {} could not safely build target-state plan ({}); aborting instead of bounded patch replay",
                        project.name(),
                        targetVersion.id(),
                        exception.getMessage()
                );
                LumaDebugLog.log(
                        project,
                        "restore",
                        "Partial restore for project {} target {} aborted instead of bounded patch replay after target-state planning was unavailable: {}",
                        project.name(),
                        targetVersion.id(),
                        exception.getMessage()
                );
                throw exception;
            }
        }
        int lineageChangeCount = reverseChanges.blockChanges().size()
                + reverseChanges.entityChanges().size()
                + forwardChanges.blockChanges().size()
                + forwardChanges.entityChanges().size();
        progressSink.update(OperationStage.PREPARING, 0, Math.max(1, lineageChangeCount), "Filtering partial restore region");
        List<StoredBlockChange> partialChanges = this.partialRestorePlanner.plan(
                pendingDraft == null ? List.of() : pendingDraft.changes(),
                reverseChanges.blockChanges(),
                forwardChanges.blockChanges(),
                request.bounds(),
                request.restoreMode()
        );
        List<StoredEntityChange> partialEntityChanges = this.planPartialEntityChanges(
                pendingDraft == null ? List.of() : pendingDraft.entityChanges(),
                reverseChanges.entityChanges(),
                forwardChanges.entityChanges(),
                request.bounds(),
                request.restoreMode()
        );
        Instant now = Instant.now();
        RecoveryDraft draft = new RecoveryDraft(
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                request.actor() == null || request.actor().isBlank() ? "Lumi" : request.actor(),
                io.github.luma.domain.model.WorldMutationSource.RESTORE,
                now,
                now,
                partialChanges,
                partialEntityChanges
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Partial restore for project {} target {} filtered {} lineage changes to {} region changes",
                project.name(),
                targetVersion.id(),
                lineageChangeCount,
                partialChanges.size() + partialEntityChanges.size()
        );
        return new PartialRestoreDraft(RestorePlanMode.PATCH_REPLAY, draft);
    }

    private PartialRestoreDraft buildTargetStatePartialRestoreDraft(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            int worldMinY,
            int worldMaxY,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        ProjectVersion currentHead = versions.stream()
                .filter(version -> version.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant head is missing: " + activeVariant.headVersionId()));
        PartialRestoreTargetStatePlanner.Plan plan = this.partialRestoreTargetStatePlanner.plan(
                layout,
                project,
                versions,
                currentHead,
                targetVersion,
                pendingDraft,
                request.bounds(),
                request.restoreMode(),
                worldMinY,
                worldMaxY,
                progressSink
        );
        Instant now = Instant.now();
        RecoveryDraft draft = new RecoveryDraft(
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
                request.actor() == null || request.actor().isBlank() ? "Lumi" : request.actor(),
                io.github.luma.domain.model.WorldMutationSource.RESTORE,
                now,
                now,
                plan.blockChanges(),
                plan.entityChanges()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Partial restore for project {} target {} used target-state planning with {} changes",
                project.name(),
                targetVersion.id(),
                plan.blockChanges().size() + plan.entityChanges().size()
        );
        return new PartialRestoreDraft(RestorePlanMode.TARGET_STATE, draft);
    }

    private List<PreparedChunkBatch> decodeWorldRootRestore(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            ServerLevel level,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ChunkPoint> trackedChunks = this.baselineChunkRepository.listChunks(layout);
        if (trackedChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing baseline chunks for world-root restore: no tracked baseline chunks"
            );
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        int index = 0;
        for (ChunkPoint chunk : trackedChunks) {
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "RestoreService.decodeWorldRootRestore.baselineChunk",
                    "chunk=" + chunk.x() + ":" + chunk.z()
            )) {
                batches.addAll(this.snapshotBatchPreparer.prepare(
                        this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                        level
                ));
            }
            index += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    index,
                    trackedChunks.size(),
                    "Decoded world root chunk " + chunk.x() + ":" + chunk.z()
            );
        }

        List<PreparedChunkBatch> collapsed = this.collapsePreparedRestoreBatches("world-root", batches);
        LumaMod.LOGGER.info(
                "Decoded {} tracked baseline chunks for world root restore in project {}",
                trackedChunks.size(),
                project.name()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "World root restore decoded {} tracked chunks into {} chunk batches",
                trackedChunks.size(),
                collapsed.size()
        );
        return collapsed;
    }

    private Optional<List<PreparedChunkBatch>> tryDecodeDirectRestore(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            ServerLevel level,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        DirectRestorePatchPlan directPlan = this.directRestorePatchPlanner.applicablePlan(project, versions, variants, targetVersion);
        if (directPlan == null) {
            LumaDebugLog.log(project, "restore", "Direct restore unavailable for project {} because no shared patch lineage was found", project.name());
            return Optional.empty();
        }

        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));
        String headVersionId = activeVariant.headVersionId();
        ExactRootStateRestorePlan exactRootStatePlan =
                this.exactRootStateRestorePlanner.plan(layout, targetVersion, pendingDraft, directPlan);

        int totalSources = directPlan.stepCount()
                + (pendingDraft != null && !pendingDraft.isEmpty() ? 1 : 0)
                + exactRootStatePlan.sourceCount();
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();
        MechanismReplayScope.Builder mechanismScope = MechanismReplayScope.builder();

        if (pendingDraft != null && !pendingDraft.isEmpty()) {
            RecoveryDraft rollbackDraft = this.entityStateResolver.alignPendingEntityRollbackWithTarget(
                    layout,
                    versions,
                    targetVersion,
                    pendingDraft
            );
            List<StoredBlockChange> rollbackChanges = rollbackDraft.changes();
            List<StoredEntityChange> rollbackEntityChanges = rollbackDraft.entityChanges();
            if (!rollbackChanges.isEmpty() || !rollbackEntityChanges.isEmpty()) {
                PreparedWorldChangeBatches analyzed = this.decodeStoredChangesAnalyzed(
                        level,
                        rollbackChanges,
                        rollbackEntityChanges,
                        false
                );
                batches.addAll(analyzed.batches());
                mechanismScope.addAll(analyzed.mechanismReplayScope());
            }
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    rollbackChanges.isEmpty() && rollbackEntityChanges.isEmpty()
                            ? "Skipped empty pending draft rollback"
                            : "Decoded pending draft rollback"
            );
        }

        for (ProjectVersion version : directPlan.reverseVersions()) {
            int before = batches.size();
            PreparedWorldChangeBatches analyzed = this.decodeVersionChangesAnalyzed(layout, level, version, false);
            batches.addAll(analyzed.batches());
            mechanismScope.addAll(analyzed.mechanismReplayScope());
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    before == batches.size()
                            ? "Skipped empty reverse patch " + version.id()
                            : "Decoded reverse patch " + version.id()
            );
        }

        for (ProjectVersion version : directPlan.forwardVersions()) {
            int before = batches.size();
            PreparedWorldChangeBatches analyzed = this.decodeVersionChangesAnalyzed(layout, level, version, true);
            batches.addAll(analyzed.batches());
            mechanismScope.addAll(analyzed.mechanismReplayScope());
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    before == batches.size()
                            ? "Skipped empty forward patch " + version.id()
                            : "Decoded forward patch " + version.id()
            );
        }

        MechanismReplayScope resolvedMechanismScope = mechanismScope.build();
        if (exactRootStatePlan.append()) {
            RestoreMechanismReplaySelection exactRootSelection =
                    this.mechanismReconciliationPlanner.selectExactRootReplayPositions(
                            project,
                            resolvedMechanismScope,
                            this.chunkCollector.blockPositions(batches),
                            level
                    );
            if (exactRootSelection.truncatedMechanismScope()) {
                LumaMod.LOGGER.warn(
                        "Direct restore for project {} to {} skipped expanded mechanism reconciliation because scope exceeded {} cells; replaying {} changed root positions",
                        project.name(),
                        targetVersion.id(),
                        RestoreMechanismReconciliationPlanner.MAX_MECHANISM_RECONCILIATION_CELLS,
                        exactRootSelection.positions().size()
                );
            }
            List<PreparedChunkBatch> exactRootBatches = this.exactRootStateRestoreDecoder.decode(
                    layout,
                    level,
                    targetVersion,
                    exactRootStatePlan,
                    exactRootSelection.positions(),
                    completedSources,
                    Math.max(1, totalSources),
                    progressSink
            ).batches();
            if (!exactRootBatches.isEmpty()) {
                batches.addAll(exactRootBatches);
            }
        } else if (!resolvedMechanismScope.isEmpty()) {
            Optional<List<BlockPoint>> mechanismPositions = this.mechanismReconciliationPlanner.boundedMechanismReplayPositions(
                    project,
                    resolvedMechanismScope,
                    level
            );
            if (mechanismPositions.isEmpty()) {
                LumaMod.LOGGER.info(
                        "Direct restore for project {} to {} skipped because mechanism target-state scope exceeded {} cells",
                        project.name(),
                        targetVersion.id(),
                        RestoreMechanismReconciliationPlanner.MAX_MECHANISM_RECONCILIATION_CELLS
                );
                return Optional.empty();
            }
            Map<BlockPoint, StatePayload> targetStates = this.blockTargetStateResolver.resolve(
                    layout,
                    project,
                    versions,
                    targetVersion,
                    mechanismPositions.orElseThrow()
            );
            if (!targetStates.isEmpty()) {
                batches.addAll(this.batchPreparer.prepareTargetStates(
                        level,
                        targetStates,
                        PreparedBlockPlacement.ReplayHint.FORCE_FINAL_REPLAY_AND_SUPPRESS_POST_REPLAY_MECHANISM
                ));
                progressSink.update(
                        OperationStage.PREPARING,
                        completedSources,
                        Math.max(1, totalSources),
                        "Decoded mechanism target-state reconciliation"
                );
            }
        }

        List<PreparedChunkBatch> collapsed;
        collapsed = this.collapsePreparedRestoreBatches("direct-restore", batches);
        int rawPlacements = totalPlacements(batches);
        int collapsedPlacements = totalPlacements(collapsed);
        LumaMod.LOGGER.info(
                "Using direct {} restore for project {} from head {} to target {} with reverseSteps={}, forwardSteps={}, draftRollback={}, exactRootSources={}, placements {} -> {}",
                directPlan.modeLabel(),
                project.name(),
                headVersionId,
                targetVersion.id(),
                directPlan.reverseVersions().size(),
                directPlan.forwardVersions().size(),
                pendingDraft != null && !pendingDraft.isEmpty(),
                exactRootStatePlan.sourceCount(),
                rawPlacements,
                collapsedPlacements
        );
        return Optional.of(collapsed);
    }

    private List<PreparedChunkBatch> withAuthoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<PreparedChunkBatch> batches
    ) throws IOException {
        return this.entityStateResolver.withAuthoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                batches
        );
    }

    private List<ChunkPoint> worldRootFallbackBaselineScope(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) throws IOException {
        DirectRestorePatchPlan plan = this.directRestorePatchPlanner.plan(project, versions, variants, targetVersion);
        return this.worldRootBaselineScope.resolve(
                layout,
                project,
                versions,
                targetVersion,
                plan == null ? List.of() : plan.allVersions()
        );
    }

    private List<PreparedChunkBatch> decodePlan(
            ProjectLayout layout,
            ServerLevel level,
            RestorePlan plan,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        int totalSources = plan.baselineGaps().size()
                + (plan.anchor().snapshotId() == null || plan.anchor().snapshotId().isBlank() ? 0 : 1)
                + plan.patchChain().size();
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();

        for (io.github.luma.domain.model.ChunkPoint chunk : plan.baselineGaps()) {
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "RestoreService.decodePlan.baselineChunk",
                    "chunk=" + chunk.x() + ":" + chunk.z()
            )) {
                batches.addAll(this.snapshotBatchPreparer.prepare(
                        this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                        level
                ));
            }
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded baseline chunk " + chunk.x() + ":" + chunk.z());
        }

        if (plan.anchor().snapshotId() != null && !plan.anchor().snapshotId().isBlank()) {
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "RestoreService.decodePlan.anchorSnapshot",
                    "snapshot=" + plan.anchor().snapshotId()
            )) {
                batches.addAll(this.snapshotBatchPreparer.prepare(
                        this.snapshotReader.readFile(layout.snapshotFile(plan.anchor().snapshotId())),
                        level
                ));
            }
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded anchor snapshot");
        }

        for (PatchMetadata patch : plan.patchChain()) {
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "RestoreService.decodePlan.patch",
                    "patch=" + patch.id()
            )) {
                batches.addAll(this.batchPreparer.prepare(
                        level,
                        this.patchDataRepository.loadSectionWorldChanges(layout, patch),
                        true,
                        (completed, total) -> {
                        },
                        EntityApplyMode.DELTA
                ));
            }
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded patch " + patch.id());
        }

        List<PreparedChunkBatch> collapsed = this.collapsePreparedRestoreBatches("restore-plan", batches);
        int rawPlacements = totalPlacements(batches);
        int collapsedPlacements = totalPlacements(collapsed);
        if (rawPlacements != collapsedPlacements) {
            LumaMod.LOGGER.info(
                    "Collapsed restore placements from {} to {} after combining snapshot, baseline, and patch batches",
                    rawPlacements,
                    collapsedPlacements
            );
        }
        LumaDebugLog.log(
                "restore",
                "Decoded restore plan with {} raw chunk batches and placements {} -> {} after collapse",
                batches.size(),
                rawPlacements,
                collapsedPlacements
        );
        return collapsed;
    }

    private List<PreparedChunkBatch> decodeVersionChanges(
            ProjectLayout layout,
            ServerLevel level,
            ProjectVersion version,
            boolean applyNewValues
    ) throws IOException {
        return this.decodeVersionChangesAnalyzed(layout, level, version, applyNewValues).batches();
    }

    private PreparedWorldChangeBatches decodeVersionChangesAnalyzed(
            ProjectLayout layout,
            ServerLevel level,
            ProjectVersion version,
            boolean applyNewValues
    ) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        MechanismReplayScope.Builder mechanismScope = MechanismReplayScope.builder();
        for (String patchId : version.patchIds()) {
            PatchMetadata metadata;
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "PatchMetaRepository.load",
                    "patch=" + patchId
            )) {
                metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            }
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "WorldChangeBatchPreparer.preparePatch",
                    "patch=" + patchId + ", applyNewValues=" + applyNewValues
            )) {
                PatchSectionWorldChanges changes = this.patchDataRepository.loadSectionWorldChanges(layout, metadata);
                if (changes.sectionFrames().isEmpty() && changes.entityChanges().isEmpty()) {
                    continue;
                }
                PreparedWorldChangeBatches analyzed = this.batchPreparer.prepareAnalyzed(
                        level,
                        changes,
                        applyNewValues,
                        (completed, total) -> {
                        },
                        EntityApplyMode.DELTA
                );
                batches.addAll(analyzed.batches());
                mechanismScope.addAll(analyzed.mechanismReplayScope());
            }
        }
        return new PreparedWorldChangeBatches(batches, mechanismScope.build());
    }

    private List<PreparedChunkBatch> collapsePreparedRestoreBatches(String source, List<PreparedChunkBatch> batches) {
        long placements = totalPlacementsLong(batches);
        int nativeSections = totalNativeSections(batches);
        if (placements > MAX_COLLAPSE_PLACEMENTS || nativeSections > MAX_COLLAPSE_NATIVE_SECTIONS) {
            LumaMod.LOGGER.info(
                    "Skipping restore batch collapse for {} because prepared work is already large: batches={}, nativeSections={}, placements={}",
                    source,
                    batches.size(),
                    nativeSections,
                    placements
            );
            LumaDebugLog.log(
                    "restore",
                    "Skipped restore batch collapse for {} with {} batches, {} native sections, and {} placements",
                    source,
                    batches.size(),
                    nativeSections,
                    placements
            );
            return List.copyOf(batches);
        }

        try (var ignored = LumaLoadLog.measure(
                "restore",
                "PreparedChunkBatchCollapser.collapse",
                "source=" + source + ", batches=" + batches.size()
        )) {
            return this.batchCollapser.collapse(batches);
        }
    }

    private List<PreparedChunkBatch> decodeStoredChanges(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        return this.decodeStoredChangesAnalyzed(level, changes, entityChanges, applyNewValues).batches();
    }

    private PreparedWorldChangeBatches decodeStoredChangesAnalyzed(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        List<PreparedChunkBatch> batches;
        try (var ignored = LumaLoadLog.measure(
                "restore",
                "WorldChangeBatchPreparer.prepareStoredChanges",
                "blocks=" + changes.size()
                        + ", entities=" + (entityChanges == null ? 0 : entityChanges.size())
                        + ", applyNewValues=" + applyNewValues
        )) {
            PreparedWorldChangeBatches analyzed = this.batchPreparer.prepareAnalyzed(
                    level,
                    changes,
                    entityChanges,
                    applyNewValues,
                    WorldChangeBatchPreparer.ProgressListener.NO_OP,
                    EntityApplyMode.DELTA
            );
            batches = analyzed.batches();
            LumaDebugLog.log(
                    "restore",
                    "Decoded {} block and {} entity stored changes into {} grouped chunk batches using {} values",
                    changes.size(),
                    entityChanges == null ? 0 : entityChanges.size(),
                    batches.size(),
                    applyNewValues ? "new" : "old"
            );
            return analyzed;
        }
    }

    private static String chunkKey(ChunkPoint chunk) {
        return chunk.x() + ":" + chunk.z();
    }

    private List<ChunkPoint> touchedChunksForPlan(RestorePlan plan) {
        return this.chunkCollector.touchedChunksForPlan(plan.baselineGaps(), plan.patchChain());
    }

    private RestorePlanMode worldRootFallbackMode(ServerLevel level, io.github.luma.domain.model.BuildProject project) throws IOException {
        WorldOriginInfo origin = this.worldOriginRepository.load(level.getServer()).orElse(null);
        if (origin != null
                && origin.createdWithLumi()
                && !this.worldOriginRepository.matchesCurrentFingerprints(level.getServer(), project.dimensionId())) {
            return RestorePlanMode.BLOCKED_FINGERPRINT;
        }
        return RestorePlanMode.BASELINE_CHUNKS;
    }

    private List<StoredEntityChange> planPartialEntityChanges(
            List<StoredEntityChange> pendingChanges,
            List<StoredEntityChange> reverseLineageChanges,
            List<StoredEntityChange> forwardLineageChanges,
            io.github.luma.domain.model.Bounds3i bounds,
            PartialRestoreMode mode
    ) {
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        Map<String, StoredEntityChange> planned = new LinkedHashMap<>();
        for (StoredEntityChange change : pendingChanges) {
            if (effectiveMode.includes(this.entityChangeInside(change, bounds))) {
                planned.put(change.entityId(), change);
            }
        }
        for (StoredEntityChange change : reverseLineageChanges) {
            this.accumulatePartialEntityChange(planned, change.inverse(), bounds, effectiveMode);
        }
        for (StoredEntityChange change : forwardLineageChanges) {
            this.accumulatePartialEntityChange(planned, change, bounds, effectiveMode);
        }
        return planned.values().stream()
                .filter(change -> !change.isNoOp())
                .toList();
    }

    private void accumulatePartialEntityChange(
            Map<String, StoredEntityChange> planned,
            StoredEntityChange target,
            io.github.luma.domain.model.Bounds3i bounds,
            PartialRestoreMode mode
    ) {
        if (!mode.includes(this.entityChangeInside(target, bounds))) {
            return;
        }
        StoredEntityChange current = planned.get(target.entityId());
        planned.put(target.entityId(), current == null ? target : current.withLatestState(target.newValue()));
    }

    private boolean entityChangeInside(StoredEntityChange change, io.github.luma.domain.model.Bounds3i bounds) {
        if (change == null || bounds == null) {
            return false;
        }
        BlockPos pos = change.newValue() == null
                ? change.oldValue().blockPos()
                : change.newValue().blockPos();
        return bounds.contains(io.github.luma.domain.model.BlockPoint.from(pos));
    }

    private static int totalPlacements(List<PreparedChunkBatch> batches) {
        long total = totalPlacementsLong(batches);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static long totalPlacementsLong(List<PreparedChunkBatch> batches) {
        long total = 0L;
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch == null) {
                continue;
            }
            total += batch.placements().size();
            for (var section : batch.nativeSections()) {
                total += section.changedCellCount();
            }
        }
        return total;
    }

    private static int totalNativeSections(List<PreparedChunkBatch> batches) {
        int total = 0;
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch != null) {
                total += batch.nativeSections().size();
            }
        }
        return total;
    }

    private record PartialRestoreDraft(RestorePlanMode mode, RecoveryDraft draft) {
    }

}
