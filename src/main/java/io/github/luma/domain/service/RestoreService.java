package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.RestorePlanSummary;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldOriginInfo;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final SnapshotBatchPreparer snapshotBatchPreparer = new SnapshotBatchPreparer();
    private final WorldChangeBatchPreparer batchPreparer = new WorldChangeBatchPreparer();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    /**
     * Starts a restore operation for the given project and target version.
     *
     * <p>If configured, the current pending draft is first saved as a restore
     * checkpoint so the player can return to the pre-restore state.
     */
    public OperationHandle restore(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));

        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVersion version = this.resolveVersion(project, versions, variants, versionId);
        ProjectVariant targetVariant = variants.stream()
                .filter(candidate -> candidate.id().equals(version.variantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version branch is missing: " + version.variantId()));
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> frozenSession = HistoryCaptureManager.getInstance()
                .freezeSession(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> frozenDraft = frozenSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft pendingDraft = frozenDraft
                .or(() -> persistedDraft)
                .orElse(null);
        LumaMod.LOGGER.info(
                "Starting restore request for project {} to version {} on variant {}",
                project.name(),
                version.id(),
                version.variantId()
        );
        LumaDebugLog.log(
                project,
                "restore",
                "Starting restore for project {} to version {} on variant {} using pendingDraft={} from {}",
                project.name(),
                version.id(),
                version.variantId(),
                pendingDraft != null && !pendingDraft.isEmpty(),
                frozenDraft.isPresent() ? "frozen live buffer" : (persistedDraft.isPresent() ? "persisted draft" : "none")
        );

        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "restore-started",
                "Started restore to version " + version.id(),
                version.id(),
                version.variantId()
        ));

        return this.worldOperationManager.startPreparedApplyOperation(
                level,
                project.id().toString(),
                "restore-version",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> {
                    if (project.settings().safetySnapshotBeforeRestore() && pendingDraft != null && !pendingDraft.isEmpty()) {
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
                        this.versionService.writeVersion(
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
                    }

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
                    return new WorldOperationManager.PreparedApplyOperation(
                            batches,
                            () -> this.completeRestore(level, layout, project, variants, targetVariant, version, batches.size())
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

        if (targetVersion.id().equals(baseVersionId)) {
            return new RestorePlanSummary(
                    RestorePlanMode.NO_OP,
                    List.of(),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        List<ProjectVersion> directVersions = this.directRestorePatchVersions(project, versions, variants, targetVersion);
        if (directVersions != null) {
            return new RestorePlanSummary(
                    RestorePlanMode.PATCH_REPLAY,
                    this.touchedChunksForVersions(layout, directVersions),
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        if (targetVersion.versionKind() == VersionKind.WORLD_ROOT || targetVersion.versionKind() == VersionKind.INITIAL) {
            List<ChunkPoint> trackedChunks = this.baselineChunkRepository.listChunks(layout);
            return new RestorePlanSummary(
                    this.worldRootFallbackMode(level, project),
                    trackedChunks,
                    targetVersion.variantId(),
                    baseVersionId,
                    targetVersion.id()
            );
        }

        RestorePlan plan = this.buildPlan(layout, project, versions, targetVersion);
        return new RestorePlanSummary(
                RestorePlanMode.BASELINE_CHUNKS,
                this.touchedChunksForPlan(plan),
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
                .freezeSession(level.getServer(), project.id().toString());
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
                            progressSink
                    );
                    if (partialDraft.draft().isEmpty()) {
                        throw new IllegalArgumentException("Partial restore has no changes inside the selected region");
                    }
                    List<PreparedChunkBatch> batches = collapsePreparedBatches(this.decodeStoredChanges(
                            level,
                            partialDraft.draft().changes(),
                            partialDraft.draft().entityChanges(),
                            true
                    ));
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
                (stage, completed, total, detail) -> {
                }
        );

        return new PartialRestorePlanSummary(
                draft.draft().changes().isEmpty() ? RestorePlanMode.NO_OP : RestorePlanMode.PATCH_REPLAY,
                request.bounds(),
                request.regionSource(),
                ChunkSelectionFactory.fromStoredChanges(draft.draft().changes()),
                activeVariant.id(),
                activeVariant.headVersionId(),
                targetVersion.id(),
                draft.draft().changes().size()
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
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ProjectVersion> directVersions = this.directRestorePatchVersions(project, versions, variants, targetVersion);
        if (directVersions == null) {
            throw new IllegalArgumentException("Partial restore currently requires a target on the active variant lineage");
        }

        Map<String, ProjectVersion> versionMap = this.versionMap(versions);
        boolean applyNewValues = this.isAncestor(versionMap, activeVariant.headVersionId(), targetVersion.id());
        PatchWorldChanges lineageChanges = this.loadVersionWorldChanges(
                layout,
                directVersions,
                this.chunksIntersecting(request.bounds())
        );
        int lineageChangeCount = lineageChanges.blockChanges().size() + lineageChanges.entityChanges().size();
        progressSink.update(OperationStage.PREPARING, 0, Math.max(1, lineageChangeCount), "Filtering partial restore region");
        List<StoredBlockChange> partialChanges = this.partialRestorePlanner.plan(
                pendingDraft == null ? List.of() : pendingDraft.changes(),
                lineageChanges.blockChanges(),
                applyNewValues,
                request.bounds()
        );
        List<StoredEntityChange> partialEntityChanges = this.planPartialEntityChanges(
                pendingDraft == null ? List.of() : pendingDraft.entityChanges(),
                lineageChanges.entityChanges(),
                applyNewValues,
                request.bounds()
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
        return new PartialRestoreDraft(draft);
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
                "Partial restore to " + request.targetVersionId(),
                partialDraft.actor(),
                VersionKind.PARTIAL_RESTORE,
                true,
                progressSinkNoOp()
        );
        this.rewritePendingDraftAfterPartialRestore(layout, pendingDraft, request.bounds());
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

    private void rewritePendingDraftAfterPartialRestore(
            ProjectLayout layout,
            RecoveryDraft pendingDraft,
            io.github.luma.domain.model.Bounds3i bounds
    ) throws IOException {
        if (pendingDraft == null || pendingDraft.isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
            return;
        }
        List<StoredBlockChange> remaining = pendingDraft.changes().stream()
                .filter(change -> !bounds.contains(change.pos()))
                .toList();
        List<StoredEntityChange> remainingEntities = pendingDraft.entityChanges().stream()
                .filter(change -> !this.entityChangeInside(change, bounds))
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
            int batchCount
    ) throws IOException {
        Instant now = Instant.now();
        this.variantRepository.save(layout, this.replaceVariantHead(variants, targetVariant.id(), version.id()));
        io.github.luma.domain.model.BuildProject updatedProject = targetVariant.id().equals(project.activeVariantId())
                ? project.withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now)
                : project.withActiveVariantId(targetVariant.id(), now)
                        .withSchemaVersion(io.github.luma.domain.model.BuildProject.CURRENT_SCHEMA_VERSION);
        this.projectRepository.save(layout, updatedProject);
        this.recoveryRepository.deleteDraft(layout);
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
            batches.addAll(this.snapshotBatchPreparer.prepare(
                    this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                    level
            ));
            index += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    index,
                    trackedChunks.size(),
                    "Decoded world root chunk " + chunk.x() + ":" + chunk.z()
            );
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
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
        List<ProjectVersion> directVersions = this.directRestorePatchVersions(project, versions, variants, targetVersion);
        if (directVersions == null) {
            LumaDebugLog.log(project, "restore", "Direct restore unavailable for project {} because target is not on the active lineage", project.name());
            return Optional.empty();
        }

        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));
        String headVersionId = activeVariant.headVersionId();
        Map<String, ProjectVersion> versionMap = this.versionMap(versions);
        boolean applyNewValues = this.isAncestor(versionMap, headVersionId, targetVersion.id());

        int totalSources = directVersions.size() + (pendingDraft != null && !pendingDraft.isEmpty() ? 1 : 0);
        int completedSources = 0;
        List<PreparedChunkBatch> batches = new ArrayList<>();

        if (pendingDraft != null && !pendingDraft.isEmpty()) {
            batches.addAll(this.decodeStoredChanges(level, pendingDraft.changes(), pendingDraft.entityChanges(), false));
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    "Decoded pending draft rollback"
            );
        }

        for (ProjectVersion version : directVersions) {
            batches.addAll(this.decodeVersionChanges(layout, level, version, applyNewValues));
            completedSources += 1;
            progressSink.update(
                    OperationStage.PREPARING,
                    completedSources,
                    Math.max(1, totalSources),
                    applyNewValues
                            ? "Decoded forward patch " + version.id()
                            : "Decoded reverse patch " + version.id()
            );
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
        int rawPlacements = totalPlacements(batches);
        int collapsedPlacements = totalPlacements(collapsed);
        LumaMod.LOGGER.info(
                "Using direct {} restore for project {} from head {} to target {} with {} patch steps, draftRollback={}, placements {} -> {}",
                applyNewValues ? "forward" : "reverse",
                project.name(),
                headVersionId,
                targetVersion.id(),
                directVersions.size(),
                pendingDraft != null && !pendingDraft.isEmpty(),
                rawPlacements,
                collapsedPlacements
        );
        return Optional.of(collapsed);
    }

    List<ProjectVersion> directRestorePatchVersions(
            io.github.luma.domain.model.BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) {
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(project.activeVariantId()))
                .findFirst()
                .orElse(null);
        if (activeVariant == null || activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
            return null;
        }

        Map<String, ProjectVersion> versionMap = this.versionMap(versions);
        String headVersionId = activeVariant.headVersionId();
        if (targetVersion.id().equals(headVersionId)) {
            return List.of();
        }

        if (this.isAncestor(versionMap, targetVersion.id(), headVersionId)) {
            List<ProjectVersion> directVersions = new ArrayList<>();
            ProjectVersion cursor = versionMap.get(headVersionId);
            while (cursor != null && !cursor.id().equals(targetVersion.id())) {
                directVersions.add(cursor);
                cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                        ? null
                        : versionMap.get(cursor.parentVersionId());
            }
            return cursor == null ? null : directVersions;
        }

        if (this.isAncestor(versionMap, headVersionId, targetVersion.id())) {
            List<ProjectVersion> reversed = new ArrayList<>();
            ProjectVersion cursor = targetVersion;
            while (cursor != null && !cursor.id().equals(headVersionId)) {
                reversed.add(cursor);
                cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                        ? null
                        : versionMap.get(cursor.parentVersionId());
            }
            if (cursor == null) {
                return null;
            }

            List<ProjectVersion> directVersions = new ArrayList<>();
            for (int index = reversed.size() - 1; index >= 0; index--) {
                directVersions.add(reversed.get(index));
            }
            return directVersions;
        }

        return null;
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
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

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
            batches.addAll(this.snapshotBatchPreparer.prepare(
                    this.snapshotReader.readFile(this.baselineChunkRepository.filePath(layout, chunk)),
                    level
            ));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded baseline chunk " + chunk.x() + ":" + chunk.z());
        }

        if (plan.anchor().snapshotId() != null && !plan.anchor().snapshotId().isBlank()) {
            batches.addAll(this.snapshotBatchPreparer.prepare(
                    this.snapshotReader.readFile(layout.snapshotFile(plan.anchor().snapshotId())),
                    level
            ));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded anchor snapshot");
        }

        for (PatchMetadata patch : plan.patchChain()) {
            batches.addAll(this.batchPreparer.prepareNewValues(level, this.patchDataRepository.loadWorldChanges(layout, patch)));
            completedSources += 1;
            progressSink.update(OperationStage.PREPARING, completedSources, totalSources, "Decoded patch " + patch.id());
        }

        List<PreparedChunkBatch> collapsed = collapsePreparedBatches(batches);
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
            PatchMetadata metadata = this.patchMetaRepository.load(layout, patchId)
                    .orElseThrow(() -> new IllegalArgumentException("Patch metadata is missing for " + patchId));
            PatchWorldChanges changes = this.patchDataRepository.loadWorldChanges(layout, metadata);
            batches.addAll(this.decodeStoredChanges(level, changes.blockChanges(), changes.entityChanges(), applyNewValues));
        }
        return batches;
    }

    private List<PreparedChunkBatch> decodeStoredChanges(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        List<PreparedChunkBatch> batches = this.batchPreparer.prepare(level, changes, entityChanges, applyNewValues);
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

    private RestorePlanMode worldRootFallbackMode(ServerLevel level, io.github.luma.domain.model.BuildProject project) throws IOException {
        WorldOriginInfo origin = this.worldOriginRepository.load(level.getServer()).orElse(null);
        if (origin != null
                && origin.createdWithLumi()
                && !this.worldOriginRepository.matchesCurrentFingerprints(level.getServer(), project.dimensionId())) {
            return RestorePlanMode.BLOCKED_FINGERPRINT;
        }
        return RestorePlanMode.BASELINE_CHUNKS;
    }

    private boolean isAncestor(Map<String, ProjectVersion> versionMap, String ancestorVersionId, String descendantVersionId) {
        ProjectVersion cursor = versionMap.get(descendantVersionId);
        while (cursor != null) {
            if (cursor.id().equals(ancestorVersionId)) {
                return true;
            }
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        return false;
    }

    private Map<String, ProjectVersion> versionMap(List<ProjectVersion> versions) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }
        return versionMap;
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
        Map<ChunkPoint, LinkedHashMap<Long, PreparedBlockPlacement>> collapsed = new LinkedHashMap<>();
        Map<ChunkPoint, EntityAccumulator> collapsedEntities = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches) {
            if (batch == null) {
                continue;
            }
            LinkedHashMap<Long, PreparedBlockPlacement> chunkPlacements = collapsed.computeIfAbsent(
                    batch.chunk(),
                    ignored -> new LinkedHashMap<>()
            );
            for (PreparedBlockPlacement placement : batch.placements()) {
                long packedPos = BlockPos.asLong(placement.pos().getX(), placement.pos().getY(), placement.pos().getZ());
                chunkPlacements.put(packedPos, placement);
            }
            if (!batch.entityBatch().isEmpty()) {
                collapsedEntities.computeIfAbsent(batch.chunk(), ignored -> new EntityAccumulator())
                        .add(batch.entityBatch());
            }
        }

        List<PreparedChunkBatch> result = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(collapsed.keySet());
        chunks.addAll(collapsedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            LinkedHashMap<Long, PreparedBlockPlacement> placements = collapsed.getOrDefault(chunk, new LinkedHashMap<>());
            EntityBatch entityBatch = collapsedEntities.getOrDefault(chunk, EntityAccumulator.EMPTY).toBatch();
            if (!placements.isEmpty() || !entityBatch.isEmpty()) {
                result.add(new PreparedChunkBatch(chunk, List.copyOf(placements.values()), entityBatch));
            }
        }
        return result;
    }

    private List<StoredEntityChange> planPartialEntityChanges(
            List<StoredEntityChange> pendingChanges,
            List<StoredEntityChange> lineageChanges,
            boolean applyNewValues,
            io.github.luma.domain.model.Bounds3i bounds
    ) {
        Map<String, StoredEntityChange> planned = new LinkedHashMap<>();
        for (StoredEntityChange change : pendingChanges) {
            if (this.entityChangeInside(change, bounds)) {
                planned.put(change.entityId(), change);
            }
        }
        for (StoredEntityChange change : lineageChanges) {
            StoredEntityChange target = applyNewValues ? change : change.inverse();
            if (this.entityChangeInside(target, bounds)) {
                StoredEntityChange current = planned.get(target.entityId());
                planned.put(target.entityId(), current == null ? target : current.withLatestState(target.newValue()));
            }
        }
        return planned.values().stream()
                .filter(change -> !change.isNoOp())
                .toList();
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
        int total = 0;
        for (PreparedChunkBatch batch : batches) {
            total += batch.placements().size();
        }
        return total;
    }

    private record RestoreChain(ProjectVersion anchor, List<ProjectVersion> patchVersions) {
    }

    private record RestorePlan(
            ProjectVersion anchor,
            List<PatchMetadata> patchChain,
            List<io.github.luma.domain.model.ChunkPoint> baselineGaps
    ) {
    }

    private record PartialRestoreDraft(RecoveryDraft draft) {
    }

    private record ChunkPointAccumulator(int chunkX, int chunkZ) {
    }

    private static final class EntityAccumulator {

        private static final EntityAccumulator EMPTY = new EntityAccumulator();

        private final List<net.minecraft.nbt.CompoundTag> spawns = new ArrayList<>();
        private final List<String> removals = new ArrayList<>();
        private final List<net.minecraft.nbt.CompoundTag> updates = new ArrayList<>();

        private void add(EntityBatch batch) {
            this.spawns.addAll(batch.entitiesToSpawn());
            this.removals.addAll(batch.entityIdsToRemove());
            this.updates.addAll(batch.entitiesToUpdate());
        }

        private EntityBatch toBatch() {
            return new EntityBatch(this.spawns, this.removals, this.updates);
        }
    }
}
