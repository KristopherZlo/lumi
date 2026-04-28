package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Saves tracked edits as durable project versions.
 *
 * <p>The service consumes the live tracked buffer, writes the patch-first v3
 * history payloads, applies snapshot policy, finalizes version manifests, and
 * queues optional preview capture requests outside the critical durability path.
 */
public final class VersionService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PreviewCaptureRequestService previewCaptureRequestService = new PreviewCaptureRequestService();
    private final PreviewBoundsResolver previewBoundsResolver = new PreviewBoundsResolver();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle startSaveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        return this.startSaveVersion(level, projectName, message, author, VersionKind.MANUAL);
    }

    public OperationHandle startAmendVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> liveSession = HistoryCaptureManager.getInstance()
                .consumeSession(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> liveDraft = liveSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft draft = liveDraft
                .or(() -> persistedDraft)
                .orElseThrow(() -> new IllegalArgumentException("No pending tracked changes for " + projectName));
        if (draft.isEmpty()) {
            throw new IllegalArgumentException("No pending tracked changes for " + projectName);
        }
        LumaDebugLog.log(
                project,
                "save",
                "Starting amend for project {} from {} with {} pending changes",
                project.name(),
                liveDraft.isPresent() ? "live buffer" : "persisted draft",
                draft.changes().size()
        );

        // Keep the draft being amended isolated from new edits captured during the async operation.
        this.recoveryRepository.saveOperationDraft(layout, draft);
        this.recoveryRepository.deleteDraft(layout);

        try {
            return this.worldOperationManager.startBackgroundOperation(
                    level,
                    project.id().toString(),
                    "amend-version",
                    "blocks",
                    LumaDebugLog.enabled(project),
                    progressSink -> {
                        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
                        ProjectVariant activeVariant = variants.stream()
                                .filter(variant -> variant.id().equals(project.activeVariantId()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name()));
                        if (activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
                            throw new IllegalArgumentException("Current branch has no head version to amend");
                        }

                        ProjectVersion headVersion = this.versionRepository.load(layout, activeVariant.headVersionId())
                                .orElseThrow(() -> new IllegalArgumentException("Head version is missing: " + activeVariant.headVersionId()));
                        RecoveryDraft amendedDraft = this.buildAmendedDraft(layout, project, activeVariant, headVersion, draft);
                        if (amendedDraft.isEmpty()) {
                            throw new IllegalArgumentException("Amend would produce an empty version");
                        }
                        LumaDebugLog.log(
                                project,
                                "save",
                                "Amending head {} on variant {} for project {}: headChanges + draftChanges -> {} merged changes",
                                headVersion.id(),
                                activeVariant.id(),
                                project.name(),
                                amendedDraft.changes().size()
                        );

                        String amendMessage = message == null || message.isBlank() ? "Amended version" : message;
                        ProjectVersion amendedVersion = this.writeVersionFromOperationDraft(
                                level,
                                layout,
                                project,
                                amendedDraft,
                                amendMessage,
                                author,
                                VersionKind.MANUAL,
                                true,
                                headVersion.parentVersionId(),
                                progressSink
                        );
                        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                                Instant.now(),
                                "version-amended",
                                "Amended active branch head",
                                amendedVersion.id(),
                                activeVariant.id()
                        ));
                    }
            );
        } catch (RuntimeException exception) {
            this.restoreOperationDraftIfNoLiveDraft(layout, project);
            throw exception;
        }
    }

    /**
     * Starts an asynchronous save operation for the current tracked changes.
     *
     * <p>The durable version manifest is only written after the background
     * operation completes successfully. Until then, the current draft is kept in
     * isolated operation storage so new edits start a separate live draft.
     */
    public OperationHandle startSaveVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            VersionKind versionKind
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        Optional<TrackedChangeBuffer> liveSession = HistoryCaptureManager.getInstance()
                .consumeSession(level.getServer(), project.id().toString());
        Optional<RecoveryDraft> liveDraft = liveSession.map(TrackedChangeBuffer::toDraft);
        RecoveryDraft draft = liveDraft
                .or(() -> persistedDraft)
                .orElseThrow(() -> new IllegalArgumentException("No pending tracked changes for " + projectName));
        if (draft.isEmpty()) {
            throw new IllegalArgumentException("No pending tracked changes for " + projectName);
        }

        LumaMod.LOGGER.info(
                "Starting save request for project {} on variant {} with {} pending changes",
                projectName,
                draft.variantId(),
                draft.changes().size()
        );
        LumaDebugLog.log(
                project,
                "save",
                "Starting save for project {} using {} with {} pending changes on variant {}",
                project.name(),
                liveDraft.isPresent() ? "live buffer" : "persisted draft",
                draft.changes().size(),
                draft.variantId()
        );

        // Keep a durable fallback until the async save fully commits, without exposing it to live capture.
        this.recoveryRepository.saveOperationDraft(layout, draft);
        this.recoveryRepository.deleteDraft(layout);

        try {
            return this.worldOperationManager.startBackgroundOperation(
                    level,
                    project.id().toString(),
                    "save-version",
                    "blocks",
                    LumaDebugLog.enabled(project),
                    progressSink -> this.writeVersionFromOperationDraft(
                            level,
                            layout,
                            project,
                            draft,
                            message,
                            author,
                            versionKind,
                            true,
                            "",
                            progressSink
                    )
            );
        } catch (RuntimeException exception) {
            this.restoreOperationDraftIfNoLiveDraft(layout, project);
            throw exception;
        }
    }

    public ProjectVersion refreshPreview(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        Bounds3i bounds = this.previewBoundsResolver.resolve(layout, project, versions, version, null, level);
        this.previewCaptureRequestService.queue(layout, versionId, project.dimensionId(), bounds);
        return version;
    }

    ProjectVersion writeVersion(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            VersionKind versionKind,
            boolean schedulePreview,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        return this.writeVersion(
                level,
                layout,
                project,
                draft,
                message,
                author,
                versionKind,
                schedulePreview,
                "",
                progressSink
        );
    }

    ProjectVersion writeVersion(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            VersionKind versionKind,
            boolean schedulePreview,
            String parentVersionIdOverride,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(draft.variantId()))
                .findFirst()
                .orElseGet(() -> variants.stream()
                        .filter(variant -> variant.id().equals(project.activeVariantId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name())));
        String parentVersionId = parentVersionIdOverride == null || parentVersionIdOverride.isBlank()
                ? activeVariant.headVersionId()
                : parentVersionIdOverride;
        LumaDebugLog.log(
                project,
                "save",
                "Preparing writeVersion for project {} variant {} parent={} kind={} schedulePreview={}",
                project.name(),
                activeVariant.id(),
                parentVersionId,
                versionKind,
                schedulePreview
        );

        int nextIndex = versions.size() + 1;
        Instant now = Instant.now();
        String versionId = ProjectService.versionId(nextIndex);
        String patchId = ProjectService.patchId(nextIndex);
        ChangeStats stats = ChangeStatsFactory.summarize(draft.changes());
        LumaMod.LOGGER.info(
                "Preparing version {} for project {}: {} blocks and {} entities across {} chunks",
                versionId,
                project.name(),
                stats.changedBlocks(),
                draft.entityChanges().size(),
                stats.changedChunks()
        );

        progressSink.update(OperationStage.PREPARING, 0, draft.totalChangeCount(), "Preparing version payload");
        var patchMetadata = this.patchDataRepository.writePayload(
                layout,
                patchId,
                project.id().toString(),
                versionId,
                draft.changes(),
                draft.entityChanges()
        );
        progressSink.update(OperationStage.WRITING, draft.totalChangeCount(), draft.totalChangeCount(), "Writing patch index");
        this.patchMetaRepository.save(layout, patchMetadata);

        String snapshotId = "";
        boolean createSnapshot = (parentVersionId == null || parentVersionId.isBlank())
                || this.shouldCreateSnapshot(project, layout, versions, activeVariant, draft, stats, versionKind);
        LumaMod.LOGGER.info(
                "Snapshot policy for version {} in project {} resolved to {}",
                versionId,
                project.name(),
                createSnapshot
        );
        if (createSnapshot) {
            snapshotId = ProjectService.snapshotId(nextIndex);
            progressSink.update(OperationStage.WRITING, draft.changes().size(), draft.changes().size(), "Capturing snapshot");
            List<ChunkPoint> snapshotChunks = this.collectSnapshotChunks(layout, project, versions, draft);
            LumaDebugLog.log(
                    project,
                    "save",
                    "Capturing snapshot {} for version {} across {} tracked chunks",
                    snapshotId,
                    versionId,
                    snapshotChunks.size()
            );
            this.snapshotWriter.capture(
                    layout,
                    project.id().toString(),
                    snapshotId,
                    snapshotChunks,
                    level,
                    now
            );
        }

        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                activeVariant.id(),
                parentVersionId == null ? "" : parentVersionId,
                snapshotId,
                List.of(patchMetadata.id()),
                project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind,
                author,
                this.resolveMessage(message, project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind),
                stats,
                PreviewInfo.none(),
                this.resolveSourceInfo(project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind),
                now
        );

        progressSink.update(OperationStage.FINALIZING, draft.changes().size(), draft.changes().size(), "Finalizing version");
        this.versionRepository.save(layout, version);
        this.variantRepository.save(layout, this.replaceVariant(variants, new ProjectVariant(
                activeVariant.id(),
                activeVariant.name(),
                activeVariant.baseVersionId(),
                version.id(),
                activeVariant.main(),
                activeVariant.createdAt()
        )));
        this.projectRepository.save(layout, project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-saved",
                this.resolveJournalMessage(version.versionKind()),
                version.id(),
                activeVariant.id()
        ));
        HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        LumaMod.LOGGER.info(
                "Committed version {} for project {} with snapshot={} and patch={}",
                version.id(),
                project.name(),
                version.snapshotId(),
                patchMetadata.id()
        );

        if (schedulePreview && project.settings().previewGenerationEnabled()) {
            Bounds3i bounds = this.previewBoundsResolver.resolve(layout, project, versions, version, draft, level);
            this.previewCaptureRequestService.queue(layout, version.id(), project.dimensionId(), bounds);
            LumaDebugLog.log(project, "preview", "Queued preview capture request for version {} with bounds {}", version.id(), bounds);
        }

        return version;
    }

    private ProjectVersion writeVersionFromOperationDraft(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            VersionKind versionKind,
            boolean schedulePreview,
            String parentVersionIdOverride,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        try {
            ProjectVersion version = this.writeVersion(
                    level,
                    layout,
                    project,
                    draft,
                    message,
                    author,
                    versionKind,
                    schedulePreview,
                    parentVersionIdOverride,
                    progressSink
            );
            this.recoveryRepository.deleteOperationDraft(layout);
            return version;
        } catch (IOException | RuntimeException exception) {
            this.restoreOperationDraftIfNoLiveDraft(layout, project);
            throw exception;
        }
    }

    private void restoreOperationDraftIfNoLiveDraft(ProjectLayout layout, BuildProject project) {
        try {
            if (this.recoveryRepository.loadDraft(layout).isPresent()) {
                return;
            }
            Optional<RecoveryDraft> operationDraft = this.recoveryRepository.loadOperationDraft(layout);
            if (operationDraft.isEmpty()) {
                return;
            }
            this.recoveryRepository.saveDraft(layout, operationDraft.get());
            LumaMod.LOGGER.warn(
                    "Restored operation draft for project {} after save/amend failure",
                    project.name()
            );
            LumaDebugLog.log(
                    project,
                    "save",
                    "Restored operation draft for project {} after save/amend failure",
                    project.name()
            );
        } catch (IOException recoveryException) {
            LumaMod.LOGGER.warn(
                    "Failed to restore operation draft for project {} after save/amend failure",
                    project.name(),
                    recoveryException
            );
        }
    }

    static List<StoredBlockChange> mergeChanges(List<StoredBlockChange> baseChanges, List<StoredBlockChange> overlayChanges) {
        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        accumulator.addBlockChanges(baseChanges);
        accumulator.addBlockChanges(overlayChanges);
        return accumulator.blockChanges();
    }

    static List<StoredEntityChange> mergeEntityChanges(
            List<StoredEntityChange> baseChanges,
            List<StoredEntityChange> overlayChanges
    ) {
        StoredChangeAccumulator accumulator = new StoredChangeAccumulator();
        accumulator.addEntityChanges(baseChanges);
        accumulator.addEntityChanges(overlayChanges);
        return accumulator.entityChanges();
    }

    RecoveryDraft buildAmendedDraft(
            ProjectLayout layout,
            BuildProject project,
            ProjectVariant activeVariant,
            ProjectVersion headVersion,
            RecoveryDraft draft
    ) throws IOException {
        PatchWorldChanges headChanges = this.loadPatchWorldChanges(layout, headVersion.patchIds());
        List<StoredBlockChange> mergedChanges = mergeChanges(headChanges.blockChanges(), draft.changes());
        List<StoredEntityChange> mergedEntityChanges = mergeEntityChanges(headChanges.entityChanges(), draft.entityChanges());
        LumaDebugLog.log(
                project,
                "save",
                "Merged amend draft for project {}: head={} blocks/{} entities, overlay={} blocks/{} entities, merged={} blocks/{} entities",
                project.name(),
                headChanges.blockChanges().size(),
                headChanges.entityChanges().size(),
                draft.changes().size(),
                draft.entityChanges().size(),
                mergedChanges.size(),
                mergedEntityChanges.size()
        );
        return new RecoveryDraft(
                project.id().toString(),
                activeVariant.id(),
                headVersion.parentVersionId(),
                draft.actor(),
                draft.mutationSource(),
                draft.startedAt(),
                draft.updatedAt(),
                mergedChanges,
                mergedEntityChanges
        );
    }

    private PatchWorldChanges loadPatchWorldChanges(ProjectLayout layout, List<String> patchIds) throws IOException {
        List<StoredBlockChange> blockChanges = new ArrayList<>();
        List<StoredEntityChange> entityChanges = new ArrayList<>();
        for (String patchId : patchIds) {
            Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
            if (metadata.isEmpty()) {
                continue;
            }
            PatchWorldChanges changes = this.patchDataRepository.loadWorldChanges(layout, metadata.get());
            blockChanges.addAll(changes.blockChanges());
            entityChanges.addAll(changes.entityChanges());
        }
        return new PatchWorldChanges(blockChanges, entityChanges);
    }

    private boolean shouldCreateSnapshot(
            BuildProject project,
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVariant activeVariant,
            RecoveryDraft draft,
            ChangeStats stats,
            VersionKind versionKind
    ) throws IOException {
        VersionKind effectiveKind = project.isLegacySnapshotProject() ? VersionKind.LEGACY : versionKind;
        if (effectiveKind == VersionKind.INITIAL || effectiveKind == VersionKind.LEGACY) {
            LumaDebugLog.log(
                    project,
                    "save",
                    "Snapshot required for project {} because version kind is {}",
                    project.name(),
                    effectiveKind
            );
            return true;
        }
        if (versions.isEmpty()) {
            LumaDebugLog.log(project, "save", "Snapshot required for project {} because no versions exist yet", project.name());
            return true;
        }
        int versionsSinceSnapshot = this.versionsSinceSnapshot(versions, activeVariant.headVersionId());
        if (versionsSinceSnapshot >= project.settings().snapshotEveryVersions()) {
            LumaDebugLog.log(
                    project,
                    "save",
                    "Snapshot required for project {} because versionsSinceSnapshot={} reached limit={}",
                    project.name(),
                    versionsSinceSnapshot,
                    project.settings().snapshotEveryVersions()
            );
            return true;
        }
        if (project.tracksWholeDimension()) {
            LumaDebugLog.log(
                    project,
                    "save",
                    "Snapshot skipped for whole-dimension project {} because cadence has not been reached",
                    project.name()
            );
            return false;
        }
        boolean exceedsThreshold = this.exceedsSnapshotVolumeThreshold(project, layout, draft, stats);
        LumaDebugLog.log(
                project,
                "save",
                "Snapshot threshold check for project {}: versionsSinceSnapshot={} limit={} changedBlocks={} changedChunks={} threshold={} exceeded={}",
                project.name(),
                versionsSinceSnapshot,
                project.settings().snapshotEveryVersions(),
                stats.changedBlocks(),
                stats.changedChunks(),
                project.settings().snapshotVolumeThreshold(),
                exceedsThreshold
        );
        return exceedsThreshold;
    }

    int versionsSinceSnapshot(List<ProjectVersion> versions, String headVersionId) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        int count = 0;
        ProjectVersion cursor = headVersionId == null || headVersionId.isBlank() ? null : versionMap.get(headVersionId);
        while (cursor != null) {
            if ((cursor.snapshotId() != null && !cursor.snapshotId().isBlank())
                    || cursor.versionKind() == VersionKind.WORLD_ROOT) {
                return count;
            }
            count += 1;
            cursor = cursor.parentVersionId() == null || cursor.parentVersionId().isBlank()
                    ? null
                    : versionMap.get(cursor.parentVersionId());
        }
        return Integer.MAX_VALUE;
    }

    private boolean exceedsSnapshotVolumeThreshold(
            BuildProject project,
            ProjectLayout layout,
            RecoveryDraft draft,
            ChangeStats stats
    ) throws IOException {
        double threshold = project.settings().snapshotVolumeThreshold();
        if (!project.tracksWholeDimension()) {
            long volume = Math.max(1L, project.bounds().volume());
            double fraction = (double) stats.changedBlocks() / (double) volume;
            LumaDebugLog.log(
                    project,
                    "save",
                    "Bounded snapshot volume check for project {}: changedBlocks={} volume={} fraction={}",
                    project.name(),
                    stats.changedBlocks(),
                    volume,
                    fraction
            );
            return fraction >= threshold;
        }

        List<ChunkPoint> knownChunks = new ArrayList<>(this.baselineChunkRepository.listChunks(layout));
        knownChunks = ChunkSelectionFactory.merge(knownChunks, ChunkSelectionFactory.fromStoredChanges(draft.changes()));
        int knownChunkCount = Math.max(1, knownChunks.size());
        int changedChunkCount = ChunkSelectionFactory.fromStoredChanges(draft.changes()).size();
        double fraction = (double) changedChunkCount / (double) knownChunkCount;
        LumaDebugLog.log(
                project,
                "save",
                "Whole-dimension snapshot volume check for project {}: changedChunks={} knownChunks={} fraction={}",
                project.name(),
                changedChunkCount,
                knownChunkCount,
                fraction
        );
        return fraction >= threshold;
    }

    private List<ChunkPoint> collectSnapshotChunks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            RecoveryDraft draft
    ) throws IOException {
        if (!project.tracksWholeDimension()) {
            return ChunkSelectionFactory.fromBounds(project.bounds());
        }

        List<ChunkPoint> chunks = new ArrayList<>(this.baselineChunkRepository.listChunks(layout));
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    continue;
                }
                for (var chunk : metadata.get().chunks()) {
                    chunks = ChunkSelectionFactory.merge(chunks, List.of(chunk.chunk()));
                }
            }
        }

        if (draft == null || draft.isEmpty()) {
            LumaDebugLog.log(project, "save", "Collected {} snapshot chunks for project {} without live draft", chunks.size(), project.name());
            return List.copyOf(chunks);
        }

        List<ChunkPoint> merged = ChunkSelectionFactory.merge(chunks, ChunkSelectionFactory.fromStoredChanges(draft.changes()));
        LumaDebugLog.log(
                project,
                "save",
                "Collected {} snapshot chunks for project {} including {} draft changes",
                merged.size(),
                project.name(),
                draft.changes().size()
        );
        return merged;
    }

    private List<ProjectVariant> replaceVariant(List<ProjectVariant> variants, ProjectVariant updatedVariant) {
        List<ProjectVariant> result = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            result.add(variant.id().equals(updatedVariant.id()) ? updatedVariant : variant);
        }
        return result;
    }

    private String resolveMessage(String message, VersionKind versionKind) {
        if (message != null && !message.isBlank()) {
            return message;
        }

        return switch (versionKind) {
            case WORLD_ROOT -> "World root";
            case RECOVERY -> "Recovered draft";
            case LEGACY -> "Migrated legacy save";
            case RESTORE -> "Restore safety checkpoint";
            case PARTIAL_RESTORE -> "Partial restore";
            case INITIAL, MANUAL -> "Saved version";
        };
    }

    private ExternalSourceInfo resolveSourceInfo(VersionKind versionKind) {
        return switch (versionKind) {
            case WORLD_ROOT -> ExternalSourceInfo.manual();
            case RECOVERY -> ExternalSourceInfo.recovery();
            case RESTORE -> ExternalSourceInfo.restore();
            case PARTIAL_RESTORE -> ExternalSourceInfo.external(
                    "SYSTEM",
                    "partial-restore",
                    "Partial Restore",
                    "",
                    null,
                    false,
                    false,
                    Map.of()
            );
            case INITIAL, MANUAL, LEGACY -> ExternalSourceInfo.manual();
        };
    }

    private String resolveJournalMessage(VersionKind versionKind) {
        return switch (versionKind) {
            case WORLD_ROOT -> "Created workspace root version";
            case RECOVERY -> "Saved recovery draft as a new version";
            case LEGACY -> "Saved a new version while migrating a legacy snapshot project";
            case RESTORE -> "Saved restore checkpoint version";
            case PARTIAL_RESTORE -> "Saved partial restore as a new version";
            case INITIAL, MANUAL -> "Saved version from tracked changes";
        };
    }

}
