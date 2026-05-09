package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
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
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.SectionChangeMask;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final WorldOriginRepository worldOriginRepository = new WorldOriginRepository();
    private final VersionService versionService = new VersionService();
    private final PartialRestorePlanner partialRestorePlanner = new PartialRestorePlanner();
    private final PartialRestoreTargetStatePlanner partialRestoreTargetStatePlanner = new PartialRestoreTargetStatePlanner();
    private final UndoRedoHistoryManager undoRedoHistoryManager = UndoRedoHistoryManager.getInstance();
    private final SnapshotBatchPreparer snapshotBatchPreparer = new SnapshotBatchPreparer();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final PreparedChunkBatchCollapser batchCollapser = new PreparedChunkBatchCollapser();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final VersionLineageService lineageService = new VersionLineageService();
    private final HistoryPackageSafetyScanner safetyScanner = new HistoryPackageSafetyScanner();

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

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion version = this.resolveVersion(project, versions, variants, versionId);
        ProjectVariant targetVariant = this.restoreTargetVariant(variants, version, targetVariantId);
        ProjectVariant activeVariant = this.activeVariant(project, variants);
        String activeHeadVersionId = activeVariant.headVersionId();
        this.requireTrustedImportedRestore(level, layout, project, versions, trustedImportedPackage);
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

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "restore-version",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
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
                    this.saveRestoreReturnPoint(layout, project, activeVariant, returnVersionId, version);
                    RestoreUndoAction restoreUndoAction = recordUndoRedoAction
                            ? this.quickRollbackUndoAction(
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
                            RestorePlan plan = this.buildPlan(layout, project, versions, version);
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
                            () -> this.completeRestore(
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
        );
    }

    public RestorePlanSummary summarizeRestorePlan(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.resolveVersion(project, versions, variants, versionId);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        String baseVersionId = activeVariant == null ? "" : activeVariant.headVersionId();
        List<ChunkPoint> pendingChunks = HistoryCaptureManager.getInstance()
                .snapshotDraft(level.getServer(), project.id().toString())
                .map(this::touchedChunksForDraft)
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

        DirectRestorePatchPlan directPlan = this.directRestorePatchPlan(project, versions, variants, targetVersion);
        if (directPlan != null) {
            return new RestorePlanSummary(
                    RestorePlanMode.PATCH_REPLAY,
                    this.mergeChunks(this.touchedChunksForVersions(layout, directPlan.allVersions()), pendingChunks),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT || targetVersion.versionKind() == VersionKind.INITIAL) {
            List<ChunkPoint> trackedChunks = this.baselineChunkRepository.listChunks(layout);
            return new RestorePlanSummary(
                    this.worldRootFallbackMode(level, project),
                    this.mergeChunks(trackedChunks, pendingChunks),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        RestorePlan plan = this.buildPlan(layout, project, versions, targetVersion);
        return new RestorePlanSummary(
                RestorePlanMode.BASELINE_CHUNKS,
                this.mergeChunks(this.touchedChunksForPlan(plan), pendingChunks),
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
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion targetVersion = this.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.activeVariant(project, variants);
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> frozenSession = HistoryCaptureManager.getInstance()
                .freezeWorkingDraft(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> frozenDraft = frozenSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft pendingDraft = frozenDraft
                .or(() -> persistedDraft)
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

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "partial-restore",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
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
                    if (partialDraft.draft().isEmpty()) {
                        throw new IllegalArgumentException("Partial restore has no changes inside the selected region");
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
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> this.completePartialRestore(
                                    level,
                                    layout,
                                    project,
                                    pendingDraft,
                                    request,
                                    partialDraft.draft(),
                                    batches.size()
                            )
                    );
                }
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
        ProjectVersion targetVersion = this.resolveVersion(project, versions, variants, request.targetVersionId());
        ProjectVariant activeVariant = this.activeVariant(project, variants);
        Optional<RecoveryDraft> pendingDraft = this.recoveryRepository.loadDraft(layout);

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

    private void saveRestoreReturnPoint(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            ProjectVariant activeVariant,
            String returnVersionId,
            ProjectVersion restoreTarget
    ) throws IOException {
        if (returnVersionId == null || returnVersionId.isBlank()) {
            return;
        }
        RestoreReturnPoint point = new RestoreReturnPoint(
                project.id().toString(),
                activeVariant.id(),
                returnVersionId,
                Instant.now(),
                restoreTarget.id()
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
        DirectRestorePatchPlan directPlan = this.directRestorePatchPlan(project, versions, variants, targetVersion);
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
                : this.chunksIntersecting(request.bounds());
        PatchWorldChanges reverseChanges = this.loadVersionWorldChanges(
                layout,
                directPlan.reverseVersions(),
                selectedChunks
        );
        PatchWorldChanges forwardChanges = this.loadVersionWorldChanges(
                layout,
                directPlan.forwardVersions(),
                selectedChunks
        );
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

    private void completePartialRestore(
            ServerLevel level,
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request,
            RecoveryDraft partialDraft,
            int batchCount
    ) throws IOException {
        this.versionService.writeVersion(
                level,
                layout,
                project,
                partialDraft,
                this.partialRestoreMessage(request),
                partialDraft.actor(),
                VersionKind.PARTIAL_RESTORE,
                true,
                progressSinkNoOp()
        );
        this.recordPartialRestoreUndoAction(level, project, request, partialDraft);
        this.rewritePendingDraftAfterPartialRestore(layout, pendingDraft, request.bounds(), request.restoreMode());
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "partial-restore-completed",
                "Partial restore wrote a new version from selected region",
                request.targetVersionId(),
                partialDraft.variantId()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed partial restore for project {} to version {} with {} chunk batches and {} changes",
                project.name(),
                request.targetVersionId(),
                batchCount,
                partialDraft.totalChangeCount()
        );
    }

    private void recordPartialRestoreUndoAction(
            ServerLevel level,
            io.github.luma.domain.model.BuildProject project,
            PartialRestoreRequest request,
            RecoveryDraft partialDraft
    ) {
        if (partialDraft == null || partialDraft.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        this.undoRedoHistoryManager.recordAction(
                project.id().toString(),
                level.dimension().identifier().toString(),
                "partial-restore-" + request.targetVersionId() + "-" + UUID.randomUUID(),
                partialDraft.actor(),
                partialDraft.changes(),
                partialDraft.entityChanges(),
                now
        );
    }

    private String partialRestoreMessage(PartialRestoreRequest request) {
        if (request.restoreMode() == PartialRestoreMode.OUTSIDE_SELECTED_AREA) {
            return "Restore around selection to " + request.targetVersionId();
        }
        return "Restore selection from " + request.targetVersionId();
    }

    private void rewritePendingDraftAfterPartialRestore(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            io.github.luma.domain.model.Bounds3i bounds,
            PartialRestoreMode mode
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        List<StoredBlockChange> remaining = pendingDraft.changes().stream()
                .filter(change -> !effectiveMode.includes(bounds.contains(change.pos())))
                .toList();
        List<StoredEntityChange> remainingEntities = pendingDraft.entityChanges().stream()
                .filter(change -> !effectiveMode.includes(this.entityChangeInside(change, bounds)))
                .toList();
        if (remaining.isEmpty() && remainingEntities.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        this.recoveryRepository.saveDraft(layout, new RecoveryDraft(
                pendingDraft.projectId(),
                pendingDraft.variantId(),
                pendingDraft.baseVersionId(),
                pendingDraft.actor(),
                pendingDraft.mutationSource(),
                pendingDraft.startedAt(),
                Instant.now(),
                remaining,
                remainingEntities
        ));
    }

    private ProjectVariant activeVariant(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVariant> variants
    ) {
        return variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));
    }

    private List<StoredBlockChange> loadVersionChanges(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        return this.loadVersionWorldChanges(layout, versions).blockChanges();
    }

    private PatchWorldChanges loadVersionWorldChanges(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        return this.loadVersionWorldChanges(layout, versions, null);
    }

    private PatchWorldChanges loadVersionWorldChanges(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            List<ChunkPoint> selectedChunks
    ) throws IOException {
        List<StoredBlockChange> changes = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                PatchWorldChanges worldChanges = selectedChunks == null
                        ? this.patchDataRepository.loadWorldChanges(layout, metadata)
                        : this.patchDataRepository.loadWorldChanges(layout, metadata, selectedChunks);
                changes.addAll(worldChanges.blockChanges());
                entityChanges.addAll(worldChanges.entityChanges());
            }
        }
        return new PatchWorldChanges(changes, entityChanges);
    }

    private static WorldOperationManager.ProgressSink progressSinkNoOp() {
        return (stage, completedUnits, totalUnits, detail) -> {
        };
    }

    private void completeRestore(
            ServerLevel level,
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVariant> variants,
            ProjectVariant targetVariant,
            ProjectVersion version,
            int batchCount,
            RestoreUndoAction restoreUndoAction
    ) throws IOException {
        Instant now = Instant.now();
        List<ProjectVariant> latestVariants = this.variantRepository.loadAll(layout);
        this.variantRepository.save(layout, this.replaceVariantHead(
                latestVariants.isEmpty() ? variants : latestVariants,
                targetVariant.id(),
                version.id()
        ));
        io.github.luma.domain.model.BuildProject updatedProject = targetVariant.id().equals(project.activeVariantId())
                ? project.withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now)
                : project.withActiveVariantId(targetVariant.id(), now)
                        .withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updatedProject);
        this.recoveryRepository.deleteDraft(layout);
        this.undoRedoHistoryManager.clearProject(project.id().toString());
        this.recordRestoreUndoAction(restoreUndoAction, now);
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "restore-completed",
                "Restored project state and reset branch head to version " + version.id(),
                version.id(),
                targetVariant.id()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Completed restore for project {} to version {} on variant {} with {} prepared chunk batches",
                project.name(),
                version.id(),
                targetVariant.id(),
                batchCount
        );
    }

    RestoreUndoAction quickRollbackUndoAction(
            String projectId,
            String dimensionId,
            String targetVersionId,
            RecoveryDraft pendingDraft
    ) {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            return null;
        }
        List<StoredBlockChange> changes = pendingDraft.changes().stream()
                .map(RestoreService::inverse)
                .toList();
        List<StoredEntityChange> entityChanges = pendingDraft.entityChanges().stream()
                .map(StoredEntityChange::inverse)
                .toList();
        if (changes.isEmpty() && entityChanges.isEmpty()) {
            return null;
        }
        return new RestoreUndoAction(
                "quick-rollback-" + targetVersionId + "-" + UUID.randomUUID(),
                "Lumi quick rollback",
                projectId,
                dimensionId,
                changes,
                entityChanges
        );
    }

    private void recordRestoreUndoAction(RestoreUndoAction action, Instant now) {
        if (action == null || action.isEmpty()) {
            return;
        }
        this.undoRedoHistoryManager.recordAction(
                action.projectId(),
                action.dimensionId(),
                action.actionId(),
                action.actor(),
                action.changes(),
                action.entityChanges(),
                now
        );
    }

    private static StoredBlockChange inverse(StoredBlockChange change) {
        return change.inverse();
    }

    private List<PreparedChunkBatch> decodeWorldRootRestore(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            ServerLevel level,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ChunkPoint> trackedChunks = this.baselineChunkRepository.listChunks(layout);
        if (trackedChunks.isEmpty()) {
            LumaMod.LOGGER.info("World root restore for project {} has no tracked baseline chunks yet", project.name());
            return List.of();
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

    private ProjectVersion resolveVersion(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String versionId
    ) {
        if (versionId != null && !versionId.isBlank()) {
            return versions.stream()
                    .filter(candidate -> candidate.id().equals(versionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        String activeVariantId = project.activeVariantId();
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(activeVariantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));

        return versions.stream()
                .filter(candidate -> candidate.id().equals(activeVariant.headVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant head version is missing: " + activeVariant.headVersionId()));
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
        DirectRestorePatchPlan directPlan = this.directRestorePatchPlan(project, versions, variants, targetVersion);
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
                this.exactRootStateRestorePlan(layout, targetVersion, pendingDraft, directPlan);

        int totalSources = directPlan.stepCount()
                + (pendingDraft != null && !pendingDraft.isEmpty() ? 1 : 0)
                + exactRootStatePlan.sourceCount();
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();

        if (pendingDraft != null && !pendingDraft.isEmpty()) {
            RecoveryDraft rollbackDraft = this.alignPendingEntityRollbackWithTarget(
                    layout,
                    versions,
                    targetVersion,
                    pendingDraft
            );
            List<StoredBlockChange> rollbackChanges = rollbackDraft.changes();
            List<StoredEntityChange> rollbackEntityChanges = rollbackDraft.entityChanges();
            if (!rollbackChanges.isEmpty() || !rollbackEntityChanges.isEmpty()) {
                batches.addAll(this.decodeStoredChanges(level, rollbackChanges, rollbackEntityChanges, false));
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
            batches.addAll(this.decodeVersionChanges(layout, level, version, false));
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
            batches.addAll(this.decodeVersionChanges(layout, level, version, true));
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

        List<PreparedChunkBatch> exactRootBatches = this.decodeExactRootStateRestore(
                layout,
                level,
                targetVersion,
                exactRootStatePlan,
                this.blockPositions(batches),
                completedSources,
                Math.max(1, totalSources),
                progressSink
        ).batches();
        if (!exactRootBatches.isEmpty()) {
            batches.addAll(exactRootBatches);
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

    private RecoveryDraft alignPendingEntityRollbackWithTarget(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.entityChanges().isEmpty()) {
            return pendingDraft;
        }

        Set<String> entityIds = new HashSet<>();
        for (StoredEntityChange change : pendingDraft.entityChanges()) {
            if (change != null && change.entityId() != null && !change.entityId().isBlank()) {
                entityIds.add(change.entityId());
            }
        }
        if (entityIds.isEmpty()) {
            return pendingDraft;
        }

        Map<String, EntityPayload> targetStates = this.targetEntityStates(layout, versions, targetVersion, entityIds);
        List<StoredEntityChange> alignedEntities = pendingDraft.entityChanges().stream()
                .map(change -> new StoredEntityChange(
                        change.entityId(),
                        change.entityType(),
                        targetStates.get(change.entityId()),
                        change.newValue()
                ))
                .filter(change -> !change.isNoOp())
                .toList();
        return new RecoveryDraft(
                pendingDraft.projectId(),
                pendingDraft.variantId(),
                pendingDraft.baseVersionId(),
                pendingDraft.actor(),
                pendingDraft.mutationSource(),
                pendingDraft.startedAt(),
                pendingDraft.updatedAt(),
                pendingDraft.changes(),
                alignedEntities
        );
    }

    private Map<String, EntityPayload> targetEntityStates(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Set<String> entityIds
    ) throws IOException {
        if (targetVersion == null || entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }

        RestoreChain chain = this.resolveChain(versions, targetVersion);
        Map<String, EntityPayload> states = new LinkedHashMap<>();
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId())).chunks()) {
                for (EntityPayload entity : chunk.entitySnapshots()) {
                    if (entityIds.contains(entity.entityId())) {
                        states.put(entity.entityId(), entity);
                    }
                }
            }
        }
        for (ProjectVersion version : chain.patchVersions()) {
            for (StoredEntityChange change : this.loadVersionWorldChanges(layout, List.of(version)).entityChanges()) {
                if (!entityIds.contains(change.entityId())) {
                    continue;
                }
                if (change.newValue() == null) {
                    states.remove(change.entityId());
                } else {
                    states.put(change.entityId(), change.newValue());
                }
            }
        }
        return states;
    }

    List<PreparedChunkBatch> withAuthoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<PreparedChunkBatch> batches
    ) throws IOException {
        List<ChunkPoint> chunks = this.batchChunks(batches);
        if (chunks.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> replacementBatches = this.authoritativeEntityReplacementBatches(
                layout,
                versions,
                targetVersionId,
                chunks
        );
        if (replacementBatches.isEmpty()) {
            return batches == null ? List.of() : batches;
        }
        List<PreparedChunkBatch> combined = new ArrayList<>(batches == null ? List.of() : batches);
        combined.addAll(replacementBatches);
        return this.batchCollapser.collapse(combined);
    }

    List<PreparedChunkBatch> authoritativeEntityReplacementBatches(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            String targetVersionId,
            List<ChunkPoint> chunks
    ) throws IOException {
        if (targetVersionId == null || targetVersionId.isBlank() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        ProjectVersion targetVersion = versions.stream()
                .filter(version -> version.id().equals(targetVersionId))
                .findFirst()
                .orElse(null);
        if (targetVersion == null) {
            return List.of();
        }

        Map<String, ChunkPoint> selectedChunks = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                selectedChunks.put(chunkKey(chunk), chunk);
            }
        }
        if (selectedChunks.isEmpty()) {
            return List.of();
        }

        Map<String, EntityPayload> targetStates;
        try {
            targetStates = this.targetEntityStatesForChunks(
                    layout,
                    versions,
                    targetVersion,
                    selectedChunks.keySet()
            );
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        Map<String, List<CompoundTag>> entitiesByChunk = new LinkedHashMap<>();
        for (ChunkPoint chunk : selectedChunks.values()) {
            entitiesByChunk.put(chunkKey(chunk), new ArrayList<>());
        }
        for (EntityPayload payload : targetStates.values()) {
            if (payload == null || payload.chunk() == null) {
                continue;
            }
            String chunkKey = chunkKey(payload.chunk());
            List<CompoundTag> entities = entitiesByChunk.get(chunkKey);
            if (entities != null) {
                entities.add(payload.copyTag());
            }
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (ChunkPoint chunk : selectedChunks.values()) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.of(),
                    EntityBatch.replacePlacedEntities(entitiesByChunk.getOrDefault(chunkKey(chunk), List.of()))
            ));
        }
        return batches;
    }

    private Map<String, EntityPayload> targetEntityStatesForChunks(
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            Set<String> selectedChunkKeys
    ) throws IOException {
        if (targetVersion == null || selectedChunkKeys == null || selectedChunkKeys.isEmpty()) {
            return Map.of();
        }

        RestoreChain chain = this.resolveChain(versions, targetVersion);
        Map<String, EntityPayload> states = new LinkedHashMap<>();
        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.readFile(layout.snapshotFile(chain.anchor().snapshotId())).chunks()) {
                if (!selectedChunkKeys.contains(chunk.chunkX() + ":" + chunk.chunkZ())) {
                    continue;
                }
                for (EntityPayload entity : chunk.entitySnapshots()) {
                    states.put(entity.entityId(), entity);
                }
            }
        }
        for (ProjectVersion version : chain.patchVersions()) {
            for (StoredEntityChange change : this.loadVersionWorldChanges(layout, List.of(version)).entityChanges()) {
                if (!this.touchesAnyChunk(change, selectedChunkKeys)) {
                    continue;
                }
                if (change.newValue() == null) {
                    states.remove(change.entityId());
                } else {
                    states.put(change.entityId(), change.newValue());
                }
            }
        }
        return states;
    }

    private boolean touchesAnyChunk(StoredEntityChange change, Set<String> selectedChunkKeys) {
        if (change == null || selectedChunkKeys == null || selectedChunkKeys.isEmpty()) {
            return false;
        }
        return this.payloadTouchesChunk(change.oldValue(), selectedChunkKeys)
                || this.payloadTouchesChunk(change.newValue(), selectedChunkKeys);
    }

    private boolean payloadTouchesChunk(EntityPayload payload, Set<String> selectedChunkKeys) {
        return payload != null
                && payload.chunk() != null
                && selectedChunkKeys.contains(chunkKey(payload.chunk()));
    }

    ExactRootStateRestorePlan exactRootStateRestorePlan(
            ProjectLayout layout,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) throws IOException {
        if (!this.shouldAppendExactRootState(targetVersion, pendingDraft, directPlan)) {
            return ExactRootStateRestorePlan.none();
        }
        List<ChunkPoint> affectedChunks = this.exactRootStateChunks(layout, pendingDraft, directPlan);
        if (affectedChunks.isEmpty()) {
            return ExactRootStateRestorePlan.none();
        }
        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT) {
            List<ChunkPoint> baselineChunks = this.availableBaselineChunks(layout, affectedChunks);
            return baselineChunks.isEmpty()
                    ? ExactRootStateRestorePlan.none()
                    : ExactRootStateRestorePlan.worldRoot(baselineChunks);
        }
        return ExactRootStateRestorePlan.initialSnapshot(affectedChunks);
    }

    boolean shouldAppendExactRootState(
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) {
        if (targetVersion == null) {
            return false;
        }
        boolean hasPendingDraft = pendingDraft != null && !pendingDraft.isEmpty();
        boolean hasPatchReplay = directPlan != null && directPlan.stepCount() > 0;
        if (!hasPendingDraft && !hasPatchReplay) {
            return false;
        }
        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT) {
            return true;
        }
        return targetVersion.versionKind() == VersionKind.INITIAL
                && targetVersion.snapshotId() != null
                && !targetVersion.snapshotId().isBlank();
    }

    private List<ChunkPoint> exactRootStateChunks(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            DirectRestorePatchPlan directPlan
    ) throws IOException {
        List<ProjectVersion> replayVersions = directPlan == null
                ? List.of()
                : directPlan.allVersions();
        return this.mergeChunks(
                this.touchedChunksForVersions(layout, replayVersions),
                this.touchedChunksForDraft(pendingDraft)
        );
    }

    private List<ChunkPoint> availableBaselineChunks(
            ProjectLayout layout,
            List<ChunkPoint> affectedChunks
    ) {
        if (affectedChunks == null || affectedChunks.isEmpty()) {
            return List.of();
        }
        return affectedChunks.stream()
                .filter(chunk -> this.baselineChunkRepository.contains(layout, chunk))
                .toList();
    }

    List<ProjectVersion> directRestorePatchVersions(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        DirectRestorePatchPlan plan = this.directRestorePatchPlan(project, versions, variants, targetVersion);
        if (plan == null || plan.isDivergent()) {
            return null;
        }
        return plan.allVersions();
    }

    DirectRestorePatchPlan directRestorePatchPlan(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        if (activeVariant == null
                || activeVariant.headVersionId() == null
                || activeVariant.headVersionId().isBlank()
                || targetVersion == null) {
            return null;
        }

        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);
        String headVersionId = activeVariant.headVersionId();
        if (targetVersion.id().equals(headVersionId)) {
            return DirectRestorePatchPlan.empty();
        }

        ProjectVersion headVersion = versionMap.get(headVersionId);
        if (headVersion == null) {
            return null;
        }

        if (this.lineageService.isAncestor(versionMap, targetVersion.id(), headVersionId)) {
            List<ProjectVersion> reverseVersions = this.pathFromHeadToAncestor(versionMap, headVersion, targetVersion.id());
            return reverseVersions == null ? null : new DirectRestorePatchPlan(reverseVersions, List.of());
        }

        if (this.lineageService.isAncestor(versionMap, headVersionId, targetVersion.id())) {
            return new DirectRestorePatchPlan(
                    List.of(),
                    this.lineageService.pathFromAncestor(versionMap, headVersionId, targetVersion.id())
            );
        }

        try {
            ProjectVersion ancestor = this.lineageService.commonAncestor(versionMap, headVersion, targetVersion);
            List<ProjectVersion> reverseVersions = this.pathFromHeadToAncestor(versionMap, headVersion, ancestor.id());
            if (reverseVersions == null) {
                return null;
            }
            return new DirectRestorePatchPlan(
                    reverseVersions,
                    this.lineageService.pathFromAncestor(versionMap, ancestor, targetVersion)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<ProjectVersion> pathFromHeadToAncestor(
            Map<String, ProjectVersion> versionMap,
            ProjectVersion headVersion,
            String ancestorVersionId
    ) {
        List<ProjectVersion> directVersions = new ArrayList<>();
        ProjectVersion cursor = headVersion;
        while (cursor != null && !cursor.id().equals(ancestorVersionId)) {
            directVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        return cursor == null ? null : directVersions;
    }

    private RestorePlan buildPlan(
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion
    ) throws IOException {
        RestoreChain chain = this.resolveChain(versions, targetVersion);
        LumaDebugLog.log(
                project,
                "restore",
                "Building restore plan for project {} target {} with anchor {}",
                project.name(),
                targetVersion.id(),
                chain.anchor().id()
        );
        List<ChunkPointAccumulator> restoredChunks = new ArrayList<>();

        if (chain.anchor().snapshotId() != null && !chain.anchor().snapshotId().isBlank()) {
            for (var chunk : this.snapshotReader.loadChunks(layout.snapshotFile(chain.anchor().snapshotId()))) {
                restoredChunks.add(new ChunkPointAccumulator(chunk.x(), chunk.z()));
            }
        }

        List<PatchMetadata> patchMetadata = new ArrayList<>();
        for (ProjectVersion patchVersion : chain.patchVersions()) {
            for (String patchId : patchVersion.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                patchMetadata.add(metadata);
                for (var chunk : metadata.chunks()) {
                    restoredChunks.add(new ChunkPointAccumulator(chunk.chunkX(), chunk.chunkZ()));
                }
            }
        }

        List<io.github.luma.domain.model.ChunkPoint> dedupedChunks = new ArrayList<>();
        Map<String, io.github.luma.domain.model.ChunkPoint> deduped = new LinkedHashMap<>();
        for (ChunkPointAccumulator chunk : restoredChunks) {
            deduped.put(chunk.chunkX + ":" + chunk.chunkZ, new io.github.luma.domain.model.ChunkPoint(chunk.chunkX, chunk.chunkZ));
        }
        dedupedChunks.addAll(deduped.values());

        List<io.github.luma.domain.model.ChunkPoint> baselineGaps = project.tracksWholeDimension()
                ? this.baselineChunkRepository.listMissingChunks(layout, dedupedChunks)
                : List.of();
        LumaDebugLog.log(
                project,
                "restore",
                "Restore plan for project {} resolved {} patch metadata entries and {} baseline gaps",
                project.name(),
                patchMetadata.size(),
                baselineGaps.size()
        );

        return new RestorePlan(chain.anchor(), patchMetadata, baselineGaps);
    }

    private RestoreChain resolveChain(List<ProjectVersion> versions, ProjectVersion targetVersion) {
        Map<String, ProjectVersion> versionMap = this.lineageService.versionMap(versions);

        List<ProjectVersion> patchVersions = new ArrayList<>();
        ProjectVersion cursor = targetVersion;
        while (cursor != null && (cursor.snapshotId() == null || cursor.snapshotId().isBlank())) {
            patchVersions.add(cursor);
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }

        if (cursor == null) {
            throw new IllegalArgumentException("No checkpoint snapshot found for version " + targetVersion.id());
        }

        patchVersions.sort(Comparator.comparing(ProjectVersion::createdAt));
        LumaMod.LOGGER.info(
                "Resolved restore chain for version {} with anchor {} and {} patch versions",
                targetVersion.id(),
                cursor.id(),
                patchVersions.size()
        );
        return new RestoreChain(cursor, patchVersions);
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
                batches.addAll(this.batchPreparer.prepareNewValues(
                        level,
                        this.patchDataRepository.loadSectionWorldChanges(layout, patch),
                        (completed, total) -> {
                        }
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
        List<PreparedChunkBatch> batches = new ArrayList<>();
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
                batches.addAll(this.batchPreparer.prepare(
                        level,
                        changes,
                        applyNewValues,
                        (completed, total) -> {
                        }
                ));
            }
        }
        return batches;
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

    ProjectVariant restoreTargetVariant(List<ProjectVariant> variants, ProjectVersion version, String targetVariantId) {
        if (targetVariantId != null && !targetVariantId.isBlank()) {
            return variants.stream()
                    .filter(candidate -> candidate.id().equals(targetVariantId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + targetVariantId));
        }
        return variants.stream()
                .filter(candidate -> candidate.id().equals(version.variantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version branch is missing: " + version.variantId()));
    }

    private void requireTrustedImportedRestore(
            ServerLevel level,
            ProjectLayout layout,
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            boolean trustedImportedPackage
    ) throws IOException {
        if (!this.isImportedReviewProject(level, project)) {
            return;
        }
        var report = this.safetyScanner.scanProjectHistory(layout, versions);
        if (report.requiresTrustedConfirmation() && !trustedImportedPackage) {
            throw new IllegalArgumentException("Imported package contains executable world-state data. Confirm that you trust this package before restoring it.");
        }
    }

    private boolean isImportedReviewProject(
            ServerLevel level,
            io.github.luma.domain.model.BuildProject project
    ) throws IOException {
        if (project == null || project.name() == null || !project.name().contains(" - Shared ")) {
            return false;
        }
        return this.projectService.listProjects(level.getServer()).stream()
                .anyMatch(candidate -> project.id().equals(candidate.id()) && !project.name().equals(candidate.name()));
    }

    private List<PreparedChunkBatch> decodeStoredChanges(
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
            batches = this.batchPreparer.prepare(level, changes, entityChanges, applyNewValues);
        }
        LumaDebugLog.log(
                "restore",
                "Decoded {} block and {} entity stored changes into {} grouped chunk batches using {} values",
                changes.size(),
                entityChanges == null ? 0 : entityChanges.size(),
                batches.size(),
                applyNewValues ? "new" : "old"
        );
        return batches;
    }

    private List<ChunkPoint> touchedChunksForVersions(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                        .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
                for (var chunk : metadata.chunks()) {
                    chunks.putIfAbsent(chunk.chunkX() + ":" + chunk.chunkZ(), new ChunkPoint(chunk.chunkX(), chunk.chunkZ()));
                }
            }
        }
        return List.copyOf(chunks.values());
    }

    private List<ChunkPoint> touchedChunksForDraft(RecoveryDraft draft) {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        if (draft == null) {
            return List.of();
        }
        for (StoredBlockChange change : draft.changes()) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
        for (StoredEntityChange change : draft.entityChanges()) {
            ChunkPoint chunk = change.chunk();
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
        return List.copyOf(chunks.values());
    }

    private List<ChunkPoint> batchChunks(List<PreparedChunkBatch> batches) {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch != null && batch.chunk() != null) {
                chunks.putIfAbsent(chunkKey(batch.chunk()), batch.chunk());
            }
        }
        return List.copyOf(chunks.values());
    }

    private static String chunkKey(ChunkPoint chunk) {
        return chunk.x() + ":" + chunk.z();
    }

    private DecodedExactRootState decodeExactRootStateRestore(
            ProjectLayout layout,
            ServerLevel level,
            ProjectVersion targetVersion,
            ExactRootStateRestorePlan plan,
            List<BlockPoint> positions,
            int completedSources,
            int totalSources,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        if (!plan.append()) {
            return new DecodedExactRootState(batches, completedSources);
        }
        if (positions == null || positions.isEmpty()) {
            return this.skipExactRootStateRestore(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state; no changed block positions"
            );
        }
        List<BlockPoint> selectedPositions = this.filterExactRootPositions(layout, targetVersion, positions);
        if (selectedPositions.isEmpty()) {
            return this.skipExactRootStateRestore(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state; no tracked baseline positions"
            );
        }

        if (targetVersion.versionKind() == VersionKind.INITIAL) {
            batches.addAll(this.snapshotBatchPreparer.preparePositions(
                    this.snapshotReader.readFile(layout.snapshotFile(targetVersion.snapshotId()), this.chunksForPositions(selectedPositions)),
                    level,
                    selectedPositions
            ));
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    totalSources,
                    "Decoded exact initial snapshot " + targetVersion.snapshotId()
                            + " for " + selectedPositions.size() + " changed positions"
            );
            return new DecodedExactRootState(batches, completedSources);
        }

        if (targetVersion.versionKind() != VersionKind.WORLD_ROOT) {
            return this.skipExactRootStateRestore(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state for unsupported target kind"
            );
        }

        List<PreparedChunkBatch> prepared = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<BlockPoint>> entry : this.positionsByChunk(selectedPositions).entrySet()) {
            ChunkPoint chunk = entry.getKey();
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "RestoreService.decodeExactRootStateRestore.baselineChunk",
                    "chunk=" + chunk.x() + ":" + chunk.z() + ", positions=" + entry.getValue().size()
            )) {
                prepared.addAll(this.snapshotBatchPreparer.preparePositions(
                        this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                        level,
                        entry.getValue()
                ));
            }
        }
        batches.addAll(prepared);
        completedSources += 1;
        progressSink.update(
                OperationStage.PREPARING,
                completedSources,
                totalSources,
                "Decoded exact root baseline for " + selectedPositions.size() + " changed positions"
        );
        return new DecodedExactRootState(batches, completedSources);
    }

    private DecodedExactRootState skipExactRootStateRestore(
            List<PreparedChunkBatch> batches,
            int completedSources,
            int totalSources,
            WorldOperationManager.ProgressSink progressSink,
            String detail
    ) {
        completedSources += 1;
        progressSink.update(OperationStage.PREPARING, completedSources, totalSources, detail);
        return new DecodedExactRootState(batches, completedSources);
    }

    private List<BlockPoint> filterExactRootPositions(
            ProjectLayout layout,
            ProjectVersion targetVersion,
            List<BlockPoint> positions
    ) {
        if (targetVersion.versionKind() != VersionKind.WORLD_ROOT) {
            return positions;
        }
        return positions.stream()
                .filter(position -> this.baselineChunkRepository.contains(layout, ChunkPoint.from(position)))
                .toList();
    }

    private List<ChunkPoint> chunksForPositions(List<BlockPoint> positions) {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (BlockPoint position : positions == null ? List.<BlockPoint>of() : positions) {
            if (position == null) {
                continue;
            }
            ChunkPoint chunk = ChunkPoint.from(position);
            chunks.putIfAbsent(chunkKey(chunk), chunk);
        }
        return List.copyOf(chunks.values());
    }

    private Map<ChunkPoint, List<BlockPoint>> positionsByChunk(List<BlockPoint> positions) {
        Map<ChunkPoint, List<BlockPoint>> grouped = new LinkedHashMap<>();
        for (BlockPoint position : positions == null ? List.<BlockPoint>of() : positions) {
            if (position == null) {
                continue;
            }
            grouped.computeIfAbsent(ChunkPoint.from(position), ignored -> new ArrayList<>())
                    .add(position);
        }
        return grouped;
    }

    private List<BlockPoint> blockPositions(List<PreparedChunkBatch> batches) {
        Map<Long, BlockPoint> positions = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch == null) {
                continue;
            }
            for (var placement : batch.placements()) {
                if (placement != null && placement.pos() != null) {
                    BlockPoint point = BlockPoint.from(placement.pos());
                    positions.putIfAbsent(placement.pos().asLong(), point);
                }
            }
            for (var section : batch.nativeSections()) {
                section.buffer().changedCells().forEachSetCell(localIndex -> {
                    BlockPoint point = new BlockPoint(
                            (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                            (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                            (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
                    );
                    positions.putIfAbsent(point.toBlockPos().asLong(), point);
                });
            }
        }
        return List.copyOf(positions.values());
    }

    private List<ChunkPoint> touchedChunksForPlan(RestorePlan plan) {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (ChunkPoint chunk : plan.baselineGaps()) {
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
        for (PatchMetadata metadata : plan.patchChain()) {
            for (var chunk : metadata.chunks()) {
                chunks.putIfAbsent(chunk.chunkX() + ":" + chunk.chunkZ(), new ChunkPoint(chunk.chunkX(), chunk.chunkZ()));
            }
        }
        return List.copyOf(chunks.values());
    }

    @SafeVarargs
    private final List<ChunkPoint> mergeChunks(List<ChunkPoint>... chunkLists) {
        Map<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (List<ChunkPoint> chunkList : chunkLists) {
            if (chunkList == null) {
                continue;
            }
            for (ChunkPoint chunk : chunkList) {
                if (chunk != null) {
                    chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
                }
            }
        }
        return List.copyOf(chunks.values());
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

    private List<ProjectVariant> replaceVariantHead(List<ProjectVariant> variants, String targetVariantId, String targetVersionId) {
        List<ProjectVariant> updated = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            if (!variant.id().equals(targetVariantId)) {
                updated.add(variant);
                continue;
            }
            updated.add(new ProjectVariant(
                    variant.id(),
                    variant.name(),
                    variant.baseVersionId(),
                    targetVersionId,
                    variant.main(),
                    variant.createdAt()
            ));
        }
        return updated;
    }

    static List<PreparedChunkBatch> collapsePreparedBatches(List<PreparedChunkBatch> batches) {
        return new PreparedChunkBatchCollapser().collapse(batches);
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

    private List<ChunkPoint> chunksIntersecting(io.github.luma.domain.model.Bounds3i bounds) {
        if (bounds == null) {
            return List.of();
        }

        List<ChunkPoint> chunks = new ArrayList<>();
        int minChunkX = Math.floorDiv(bounds.min().x(), 16);
        int maxChunkX = Math.floorDiv(bounds.max().x(), 16);
        int minChunkZ = Math.floorDiv(bounds.min().z(), 16);
        int maxChunkZ = Math.floorDiv(bounds.max().z(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPoint(chunkX, chunkZ));
            }
        }
        return chunks;
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

    record DirectRestorePatchPlan(List<ProjectVersion> reverseVersions, List<ProjectVersion> forwardVersions) {

        private static DirectRestorePatchPlan empty() {
            return new DirectRestorePatchPlan(List.of(), List.of());
        }

        DirectRestorePatchPlan {
            reverseVersions = reverseVersions == null ? List.of() : List.copyOf(reverseVersions);
            forwardVersions = forwardVersions == null ? List.of() : List.copyOf(forwardVersions);
        }

        private int stepCount() {
            return this.reverseVersions.size() + this.forwardVersions.size();
        }

        private boolean isDivergent() {
            return !this.reverseVersions.isEmpty() && !this.forwardVersions.isEmpty();
        }

        private List<ProjectVersion> allVersions() {
            List<ProjectVersion> versions = new ArrayList<>(this.stepCount());
            versions.addAll(this.reverseVersions);
            versions.addAll(this.forwardVersions);
            return List.copyOf(versions);
        }

        private String modeLabel() {
            if (this.reverseVersions.isEmpty() && this.forwardVersions.isEmpty()) {
                return "no-op";
            }
            if (this.reverseVersions.isEmpty()) {
                return "forward";
            }
            if (this.forwardVersions.isEmpty()) {
                return "reverse";
            }
            return "divergent";
        }
    }

    record ExactRootStateRestorePlan(boolean append, List<ChunkPoint> chunks) {

        private static ExactRootStateRestorePlan none() {
            return new ExactRootStateRestorePlan(false, List.of());
        }

        private static ExactRootStateRestorePlan initialSnapshot(List<ChunkPoint> chunks) {
            return new ExactRootStateRestorePlan(true, chunks);
        }

        private static ExactRootStateRestorePlan worldRoot(List<ChunkPoint> chunks) {
            return new ExactRootStateRestorePlan(true, chunks);
        }

        private int sourceCount() {
            return this.append && !this.chunks.isEmpty() ? 1 : 0;
        }

        ExactRootStateRestorePlan {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    private record DecodedExactRootState(List<PreparedChunkBatch> batches, int completedSources) {

        DecodedExactRootState {
            batches = batches == null ? List.of() : List.copyOf(batches);
        }
    }

    private record RestoreChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }

    private record RestorePlan(
            ProjectVersion anchor,
            List<PatchMetadata> patchChain,
            List<io.github.luma.domain.model.ChunkPoint> baselineGaps
    ) {
    }

    private record PartialRestoreDraft(RestorePlanMode mode, RecoveryDraft draft) {
    }

    record RestoreUndoAction(
            String actionId,
            String actor,
            String projectId,
            String dimensionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) {

        RestoreUndoAction {
            changes = changes == null ? List.of() : List.copyOf(changes);
            entityChanges = entityChanges == null ? List.of() : List.copyOf(entityChanges);
        }

        private boolean isEmpty() {
            return this.changes.isEmpty() && this.entityChanges.isEmpty();
        }
    }

    private record ChunkPointAccumulator(int chunkX, int chunkZ) {
    }

}
