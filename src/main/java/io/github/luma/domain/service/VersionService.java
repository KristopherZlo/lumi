package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StoredChangeAccumulator;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.VersionSaveTiming;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.LiveEntityChunkCollector;
import io.github.luma.minecraft.capture.PlayerRespawnCaptureService;
import io.github.luma.minecraft.capture.SnapshotCaptureService;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.PlayerRespawnRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Saves tracked edits as durable project versions.
 *
 * <p>The service consumes the durable working draft, writes the patch-first v3
 * history payloads, applies snapshot policy, finalizes version manifests, and
 * queues optional preview capture requests outside the critical durability path.
 */
public final class VersionService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotCaptureService snapshotCaptureService = new SnapshotCaptureService();
    private final PlayerRespawnCaptureService playerRespawnCaptureService = new PlayerRespawnCaptureService();
    private final LiveEntityChunkCollector liveEntityChunkCollector = new LiveEntityChunkCollector();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final PlayerRespawnRepository playerRespawnRepository = new PlayerRespawnRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final OperationDraftRecoveryService operationDraftRecoveryService = new OperationDraftRecoveryService();
    private final SaveDraftIsolationService draftIsolationService = new SaveDraftIsolationService();
    private final PreviewCaptureRequestService previewCaptureRequestService = new PreviewCaptureRequestService();
    private final PreviewBoundsResolver previewBoundsResolver = new PreviewBoundsResolver();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final VersionSnapshotPlanner snapshotPlanner = new VersionSnapshotPlanner(
            this.baselineChunkRepository,
            this.patchMetaRepository
    );
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();
    private final Map<String, VersionSaveTimingBuilder> saveTimingsByOperationId = new ConcurrentHashMap<>();

    public OperationHandle startSaveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        return this.startSaveVersion(level, projectName, message, author, VersionKind.MANUAL);
    }

    public OperationHandle startSaveVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            List<String> tags
    ) throws IOException {
        return this.startSaveVersion(level, projectName, message, author, VersionKind.MANUAL, tags);
    }

    public Optional<VersionSaveTiming> saveTiming(OperationHandle handle) {
        if (handle == null || handle.id() == null || handle.id().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.saveTimingsByOperationId.get(handle.id()))
                .map(VersionSaveTimingBuilder::snapshot);
    }

    public OperationHandle startAmendVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        return this.startAmendVersion(level, projectName, message, author, List.of());
    }

    public OperationHandle startAmendVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            List<String> tags
    ) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }

        return this.worldOperationManager.startBackgroundOperation(
                level,
                project.id().toString(),
                "amend-version",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> this.runAmendVersionOperation(level, layout, project, message, author, tags, progressSink)
        );
    }

    /**
     * Starts an asynchronous save operation for the current tracked changes.
     *
     * <p>The durable version manifest is only written after the background
     * operation completes successfully. Until then, the current draft is kept in
     * isolated operation storage and the world mutation barrier prevents a second
     * edit stream from racing snapshot and checkpoint capture.
     */
    public OperationHandle startSaveVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            VersionKind versionKind
    ) throws IOException {
        return this.startSaveVersion(level, projectName, message, author, versionKind, List.of());
    }

    public OperationHandle startSaveVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            VersionKind versionKind,
            List<String> tags
    ) throws IOException {
        VersionSaveTimingBuilder timing = new VersionSaveTimingBuilder();
        long requestStartedAt = System.nanoTime();
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }

        long sectionStartedAt = System.nanoTime();
        OperationHandle handle = this.worldOperationManager.startBackgroundOperation(
                level,
                project.id().toString(),
                "save-version",
                "blocks",
                LumaDebugLog.enabled(project),
                progressSink -> this.runSaveVersionOperation(
                        level,
                        layout,
                        project,
                        message,
                        author,
                        versionKind,
                        tags,
                        progressSink,
                        timing
                )
        );
        timing.record(VersionSaveTiming.OPERATION_QUEUE, sectionStartedAt);
        timing.record(VersionSaveTiming.REQUEST_TOTAL, requestStartedAt);
        this.saveTimingsByOperationId.put(handle.id(), timing);
        return handle;
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

    private void runSaveVersionOperation(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            String message,
            String author,
            VersionKind versionKind,
            List<String> tags,
            WorldOperationManager.ProgressSink progressSink,
            VersionSaveTimingBuilder timing
    ) throws IOException {
        IsolatedDraft isolatedDraft = this.isolateVersionDraft(level, layout, project, author, progressSink, timing);
        RecoveryDraft draft = isolatedDraft.draft();
        this.writeVersionFromOperationDraft(
                level,
                layout,
                project,
                draft,
                message,
                author,
                versionKind,
                true,
                "",
                draft.baseVersionId(),
                progressSink,
                isolatedDraft.workZone(),
                tags,
                timing
        );
    }

    private void runAmendVersionOperation(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            String message,
            String author,
            List<String> tags,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        IsolatedDraft isolatedDraft = this.isolateVersionDraft(level, layout, project, author, progressSink, null);
        RecoveryDraft draft = isolatedDraft.draft();
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
                headVersion.id(),
                progressSink,
                isolatedDraft.workZone(),
                tags,
                null
        );
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                Instant.now(),
                "version-amended",
                "Amended active branch head",
                amendedVersion.id(),
                activeVariant.id()
        ));
    }

    private IsolatedDraft isolateVersionDraft(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            String author,
            WorldOperationManager.ProgressSink progressSink,
            VersionSaveTimingBuilder timing
    ) throws IOException {
        long sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.PREPARING, 0, 0, "Recovering interrupted operation draft");
        this.operationDraftRecoveryService.restoreInterruptedOperationDraft(layout, project);
        recordTiming(timing, VersionSaveTiming.RESTORE_INTERRUPTED_DRAFT, sectionStartedAt);

        sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.PREPARING, 0, 0, "Isolating pending changes");
        EntityMutationTracker.drainPendingSpawns(level.getServer());
        Optional<TrackedChangeBuffer> liveSession = HistoryCaptureManager.getInstance()
                .consumeWorkingDraft(level.getServer(), project.id().toString());
        recordTiming(timing, VersionSaveTiming.CONSUME_WORKING_DRAFT, sectionStartedAt);
        Optional<RecoveryDraft> liveDraft = liveSession.map(TrackedChangeBuffer::toDraft);

        sectionStartedAt = System.nanoTime();
        Optional<RecoveryDraft> persistedDraft = liveDraft.isPresent()
                ? Optional.empty()
                : this.recoveryRepository.loadDraft(layout);
        recordTiming(timing, VersionSaveTiming.LOAD_PERSISTED_DRAFT, sectionStartedAt);
        RecoveryDraft draft = liveDraft
                .or(() -> persistedDraft)
                .orElseThrow(() -> new IllegalArgumentException("No pending tracked changes for " + project.name()));
        if (draft.isEmpty()) {
            throw new IllegalArgumentException("No pending tracked changes for " + project.name());
        }

        LumaMod.LOGGER.info(
                "Starting save request for project {} on variant {} with {} pending changes",
                project.name(),
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

        SaveDraftIsolationService.ScopedDraftSplit scoped =
                this.draftIsolationService.splitForActiveZone(layout, draft, author);
        DraftSplit split = scoped.split();
        if (split.selected().isEmpty()) {
            if (!split.remainder().isEmpty()) {
                this.recoveryRepository.saveDraft(layout, split.remainder());
            }
            throw new IllegalArgumentException("No pending tracked changes for active work zone");
        }
        // Keep a durable fallback until the async save fully commits, without exposing it to live capture.
        sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.WRITING, 0, split.selected().totalChangeCount(), "Writing operation draft");
        this.recoveryRepository.saveOperationDraft(layout, split.selected());
        recordTiming(timing, VersionSaveTiming.OPERATION_DRAFT_WRITE, sectionStartedAt);
        LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE);

        sectionStartedAt = System.nanoTime();
        if (split.remainder().isEmpty()) {
            this.recoveryRepository.deleteDraft(layout);
        } else {
            this.recoveryRepository.saveDraft(layout, split.remainder());
        }
        recordTiming(timing, VersionSaveTiming.RECOVERY_DRAFT_DELETE, sectionStartedAt);
        return new IsolatedDraft(split.selected(), scoped.workZone());
    }

    static DraftSplit splitDraftForZone(RecoveryDraft draft, WorkZone zone) {
        return new SaveDraftIsolationService().splitForZone(draft, zone);
    }

    public static PendingChangeSummary summarizePendingForZone(RecoveryDraft draft, WorkZone zone) {
        return new SaveDraftIsolationService().summarize(draft, zone);
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
        return this.writeVersion(
                level,
                layout,
                project,
                draft,
                message,
                author,
                versionKind,
                schedulePreview,
                parentVersionIdOverride,
                progressSink,
                null,
                null,
                List.of()
        );
    }

    ProjectVersion writeStagedVersion(
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
                progressSink,
                false,
                true,
                null,
                null,
                List.of()
        );
    }

    private ProjectVersion writeVersion(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            VersionKind versionKind,
            boolean schedulePreview,
            String parentVersionIdOverride,
            WorldOperationManager.ProgressSink progressSink,
            VersionSaveTimingBuilder timing,
            WorkZone workZone,
            List<String> tags
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
                parentVersionIdOverride,
                progressSink,
                true,
                true,
                timing,
                workZone,
                tags
        );
    }

    private ProjectVersion writeVersion(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            VersionKind versionKind,
            boolean schedulePreview,
            String parentVersionIdOverride,
            WorldOperationManager.ProgressSink progressSink,
            boolean publishHead,
            boolean allowSnapshotCapture,
            VersionSaveTimingBuilder timing,
            WorkZone workZone,
            List<String> tags
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
        ChangeStats stats;
        long sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure(
                "save",
                "VersionService.summarizeChanges",
                "project=" + project.name() + ", changes=" + draft.changes().size()
        )) {
            stats = ChangeStatsFactory.summarize(draft.changes());
        } finally {
            recordTiming(timing, VersionSaveTiming.SUMMARIZE_CHANGES, sectionStartedAt);
        }
        LumaMod.LOGGER.info(
                "Preparing version {} for project {}: {} blocks and {} entities across {} chunks",
                versionId,
                project.name(),
                stats.changedBlocks(),
                draft.entityChanges().size(),
                stats.changedChunks()
        );

        boolean createSnapshot;
        sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure("save", "VersionService.snapshotPolicy", "project=" + project.name())) {
            createSnapshot = level != null
                    && allowSnapshotCapture
                    && ((parentVersionId == null || parentVersionId.isBlank())
                    || this.snapshotPlanner.shouldCreateSnapshot(
                            project,
                            layout,
                            versions,
                            activeVariant,
                            draft,
                            stats,
                            versionKind
                    ));
        } finally {
            recordTiming(timing, VersionSaveTiming.SNAPSHOT_POLICY, sectionStartedAt);
        }
        LumaMod.LOGGER.info(
                "Snapshot policy for version {} in project {} resolved to {}",
                versionId,
                project.name(),
                createSnapshot
        );

        progressSink.update(OperationStage.PREPARING, 0, draft.totalChangeCount(), "Preparing version payload");
        String entityCheckpointId = "";
        String snapshotId = "";
        List<ChunkPoint> entityCheckpointChunks = List.of();
        if (level != null) {
            entityCheckpointId = ProjectService.entityCheckpointId(nextIndex);
            snapshotId = createSnapshot ? ProjectService.snapshotId(nextIndex) : "";
            progressSink.update(
                    OperationStage.WRITING,
                    0,
                    draft.totalChangeCount(),
                    createSnapshot ? "Capturing snapshot and entity checkpoint" : "Capturing entity checkpoint"
            );
            sectionStartedAt = System.nanoTime();
            try (var ignored = LumaLoadLog.measure(
                    "save",
                    createSnapshot
                            ? "SnapshotCaptureService.captureSnapshotAndEntityCheckpoint"
                            : "SnapshotCaptureService.captureEntityCheckpoint",
                    "project=" + project.name()
            )) {
                entityCheckpointChunks = this.snapshotPlanner.collectEntityCheckpointChunks(
                        layout,
                        project,
                        versions,
                        draft,
                        this.liveEntityChunkCollector.collect(level)
                );
                if (createSnapshot) {
                    this.snapshotCaptureService.captureSnapshotAndEntityCheckpoint(
                            layout,
                            project.id().toString(),
                            snapshotId,
                            entityCheckpointId,
                            entityCheckpointChunks,
                            level,
                            now
                    );
                } else {
                    this.snapshotCaptureService.captureEntityCheckpoint(
                            layout,
                            project.id().toString(),
                            entityCheckpointId,
                            entityCheckpointChunks,
                            level,
                            now
                    );
                }
            } finally {
                recordTiming(
                        timing,
                        createSnapshot ? VersionSaveTiming.SNAPSHOT_CAPTURE : VersionSaveTiming.ENTITY_CHECKPOINT_CAPTURE,
                        sectionStartedAt
                );
            }
        }
        PatchMetadata patchMetadata;
        sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure(
                "save",
                "PatchDataRepository.writePayload",
                "project=" + project.name()
                        + ", version=" + versionId
                        + ", blocks=" + draft.changes().size()
                        + ", entities=" + draft.entityChanges().size()
        )) {
            patchMetadata = this.patchDataRepository.writePayload(
                    layout,
                    patchId,
                    project.id().toString(),
                    versionId,
                    draft.changes(),
                    draft.entityChanges()
            );
            LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_PATCH_DATA_WRITE);
        } finally {
            recordTiming(timing, VersionSaveTiming.PATCH_PAYLOAD_WRITE, sectionStartedAt);
        }
        progressSink.update(OperationStage.WRITING, draft.totalChangeCount(), draft.totalChangeCount(), "Writing patch index");
        sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure("save", "PatchMetaRepository.save", "patch=" + patchMetadata.id())) {
            this.patchMetaRepository.save(layout, patchMetadata);
        } finally {
            recordTiming(timing, VersionSaveTiming.PATCH_META_WRITE, sectionStartedAt);
        }

        if (createSnapshot) {
            LumaDebugLog.log(
                    project,
                    "save",
                    "Captured snapshot {} for version {} across {} tracked chunks",
                    snapshotId,
                    versionId,
                    entityCheckpointChunks.size()
            );
        }

        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                activeVariant.id(),
                parentVersionId == null ? "" : parentVersionId,
                snapshotId,
                entityCheckpointId,
                List.of(patchMetadata.id()),
                versionKind,
                author,
                message != null && !message.isBlank()
                        ? message
                        : switch (versionKind) {
                            case WORLD_ROOT -> "World root";
                            case RECOVERY -> "Recovered draft";
                            case RESTORE -> "Restore safety checkpoint";
                            case PARTIAL_RESTORE -> "Partial restore";
                            case MERGE -> "Merged branches";
                            case AUTO_CHECKPOINT -> "Auto checkpoint";
                            case INITIAL, MANUAL -> "Saved version";
                        },
                stats,
                PreviewInfo.none(),
                this.sourceInfo(versionKind, workZone),
                now
        );
        if (tags != null && !tags.isEmpty()) {
            version = ProjectVersionTags.withTags(version, tags);
        }

        if (level != null) {
            this.playerRespawnRepository.saveVersion(layout, version.id(), this.playerRespawnCaptureService.capture(level));
        }

        progressSink.update(OperationStage.FINALIZING, draft.changes().size(), draft.changes().size(), "Finalizing version");
        sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure("save", "VersionService.writeVersionManifests", "version=" + version.id())) {
            LumiTestFailpoints.hit(LumiTestFailpoints.BEFORE_VERSION_MANIFEST_WRITE);
            this.versionRepository.save(layout, version);
            if (publishHead) {
                this.publishVersionMetadata(layout, project, variants, activeVariant, version, now);
            }
        } finally {
            recordTiming(timing, VersionSaveTiming.MANIFEST_WRITE, sectionStartedAt);
        }
        if (publishHead && level != null) {
            HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        }
        LumaMod.LOGGER.info(
                "{} version {} for project {} with snapshot={}, entityCheckpoint={}, and patch={}",
                publishHead ? "Committed" : "Staged",
                version.id(),
                project.name(),
                version.snapshotId(),
                version.entityCheckpointId(),
                patchMetadata.id()
        );

        if (schedulePreview && project.settings().previewGenerationEnabled()) {
            Bounds3i bounds;
            sectionStartedAt = System.nanoTime();
            try (var ignored = LumaLoadLog.measure("save", "PreviewBoundsResolver.resolve", "version=" + version.id())) {
                bounds = this.previewBoundsResolver.resolve(layout, project, versions, version, draft, level);
            }
            try (var ignored = LumaLoadLog.measure("save", "PreviewCaptureRequestService.queue", "version=" + version.id())) {
                this.previewCaptureRequestService.queue(layout, version.id(), project.dimensionId(), bounds);
            } finally {
                recordTiming(timing, VersionSaveTiming.PREVIEW_QUEUE, sectionStartedAt);
            }
            LumaDebugLog.log(project, "preview", "Queued preview capture request for version {} with bounds {}", version.id(), bounds);
        }

        return version;
    }

    private void publishVersionMetadata(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVariant> variants,
            ProjectVariant activeVariant,
            ProjectVersion version,
            Instant now
    ) throws IOException {
        LumiTestFailpoints.hit(LumiTestFailpoints.BEFORE_VARIANT_METADATA_WRITE);
        List<ProjectVariant> updatedVariants = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            updatedVariants.add(variant.id().equals(activeVariant.id())
                    ? new ProjectVariant(
                            activeVariant.id(),
                            activeVariant.name(),
                            activeVariant.baseVersionId(),
                            version.id(),
                            activeVariant.main(),
                            activeVariant.createdAt(),
                            activeVariant.switchKey()
                    )
                    : variant);
        }
        this.variantRepository.save(layout, updatedVariants);
        this.projectRepository.save(layout, project.withSchemaVersion(BuildProject.CURRENT_SCHEMA_VERSION).withUpdatedAt(now));
        this.recoveryRepository.appendJournalEntry(layout, new RecoveryJournalEntry(
                now,
                "version-saved",
                switch (version.versionKind()) {
                    case WORLD_ROOT -> "Created workspace root version";
                    case RECOVERY -> "Saved recovery draft as a new version";
                    case RESTORE -> "Saved restore checkpoint version";
                    case PARTIAL_RESTORE -> "Saved partial restore as a new version";
                    case MERGE -> "Saved branch merge as a new version";
                    case AUTO_CHECKPOINT -> "Saved automatic checkpoint before a large edit";
                    case INITIAL, MANUAL -> "Saved version from tracked changes";
                },
                version.id(),
                activeVariant.id()
        ));
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
            String rebaseFromVersionId,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        return this.writeVersionFromOperationDraft(
                level,
                layout,
                project,
                draft,
                message,
                author,
                versionKind,
                schedulePreview,
                parentVersionIdOverride,
                rebaseFromVersionId,
                progressSink,
                null,
                List.of(),
                null
        );
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
            String rebaseFromVersionId,
            WorldOperationManager.ProgressSink progressSink,
            WorkZone workZone,
            List<String> tags,
            VersionSaveTimingBuilder timing
    ) throws IOException {
        long backgroundStartedAt = System.nanoTime();
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
                    progressSink,
                    timing,
                    workZone,
                    tags
            );
            long sectionStartedAt = System.nanoTime();
            try {
                HistoryCaptureManager.getInstance().rebaseWorkingDraftBase(
                        level.getServer(),
                        project.id().toString(),
                        rebaseFromVersionId,
                        version.id()
                );
            } catch (IOException exception) {
                LumaMod.LOGGER.warn(
                        "Saved version {} for project {}, but failed to rebase the active working draft from {}",
                        version.id(),
                        project.name(),
                        rebaseFromVersionId,
                        exception
                );
            }
            recordTiming(timing, VersionSaveTiming.REBASE_WORKING_DRAFT, sectionStartedAt);
            sectionStartedAt = System.nanoTime();
            this.recoveryRepository.deleteOperationDraft(layout);
            this.markExpectedWorkZoneRemainder(level, layout, project, workZone);
            recordTiming(timing, VersionSaveTiming.OPERATION_DRAFT_DELETE, sectionStartedAt);
            return version;
        } finally {
            recordTiming(timing, VersionSaveTiming.BACKGROUND_TOTAL, backgroundStartedAt);
        }
    }

    private void markExpectedWorkZoneRemainder(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            WorkZone workZone
    ) throws IOException {
        if (level == null || workZone == null) {
            return;
        }
        if (this.recoveryRepository.loadDraft(layout).filter(draft -> !draft.isEmpty()).isEmpty()) {
            return;
        }
        HistoryCaptureManager.getInstance().markPersistedDraftCurrentRun(level.getServer(), project.id().toString());
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

    private ExternalSourceInfo sourceInfo(VersionKind versionKind, WorkZone workZone) {
        ExternalSourceInfo sourceInfo = switch (versionKind) {
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
            case MERGE -> ExternalSourceInfo.external(
                    "SYSTEM",
                    "merge",
                    "Branch Merge",
                    "",
                    null,
                    false,
                    false,
                    Map.of()
            );
            case AUTO_CHECKPOINT -> ExternalSourceInfo.external(
                    "SYSTEM",
                    "auto-checkpoint",
                    "Auto Checkpoint",
                    "",
                    null,
                    false,
                    false,
                    Map.of()
            );
            case INITIAL, MANUAL -> ExternalSourceInfo.manual();
        };
        if (workZone == null || workZone.id().isBlank()) {
            return sourceInfo;
        }
        Map<String, String> metadata = new LinkedHashMap<>(sourceInfo.metadata());
        metadata.put(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, workZone.id());
        return ExternalSourceInfo.external(
                sourceInfo.tool(),
                sourceInfo.operationType(),
                sourceInfo.operationLabel(),
                sourceInfo.actor(),
                sourceInfo.sourceBounds(),
                sourceInfo.usedClipboard(),
                sourceInfo.usedSelection(),
                metadata
        );
    }

    int versionsSinceSnapshot(List<ProjectVersion> versions, String headVersionId) {
        return this.snapshotPlanner.versionsSinceSnapshot(versions, headVersionId);
    }

    private static void recordTiming(VersionSaveTimingBuilder timing, String phase, long startedAtNanos) {
        if (timing != null) {
            timing.record(phase, startedAtNanos);
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static final class VersionSaveTimingBuilder {

        private final Map<String, Long> durationsMs = new LinkedHashMap<>();

        private synchronized void record(String phase, long startedAtNanos) {
            if (phase == null || phase.isBlank()) {
                return;
            }
            this.durationsMs.put(phase, elapsedMillis(startedAtNanos));
        }

        private synchronized VersionSaveTiming snapshot() {
            return new VersionSaveTiming(this.durationsMs);
        }
    }

    record DraftSplit(RecoveryDraft selected, RecoveryDraft remainder) {

    }

    private record IsolatedDraft(RecoveryDraft draft, WorkZone workZone) {
    }

}
