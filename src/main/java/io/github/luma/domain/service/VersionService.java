package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PatchMetadata;
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
import io.github.luma.domain.model.VersionSaveTiming;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.SnapshotCaptureService;
import io.github.luma.debug.LumiTestFailpoints;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
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
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final OperationDraftRecoveryService operationDraftRecoveryService = new OperationDraftRecoveryService();
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

    public Optional<VersionSaveTiming> saveTiming(OperationHandle handle) {
        if (handle == null || handle.id() == null || handle.id().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.saveTimingsByOperationId.get(handle.id()))
                .map(VersionSaveTimingBuilder::snapshot);
    }

    public OperationHandle startAmendVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
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
                progressSink -> this.runAmendVersionOperation(level, layout, project, message, author, progressSink)
        );
    }

    /**
     * Starts an asynchronous save operation for the current tracked changes.
     *
     * <p>The durable version manifest is only written after the background
     * operation completes successfully. Until then, the current draft is kept in
     * isolated operation storage so new edits start a separate working draft.
     */
    public OperationHandle startSaveVersion(
            ServerLevel level,
            String projectName,
            String message,
            String author,
            VersionKind versionKind
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
            WorldOperationManager.ProgressSink progressSink,
            VersionSaveTimingBuilder timing
    ) throws IOException {
        IsolatedDraft isolatedDraft = this.isolateVersionDraft(level, layout, project, progressSink, timing);
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
                timing
        );
    }

    private void runAmendVersionOperation(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            String message,
            String author,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        IsolatedDraft isolatedDraft = this.isolateVersionDraft(level, layout, project, progressSink, null);
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

    private IsolatedDraft isolateVersionDraft(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            WorldOperationManager.ProgressSink progressSink,
            VersionSaveTimingBuilder timing
    ) throws IOException {
        long sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.PREPARING, 0, 0, "Recovering interrupted operation draft");
        this.operationDraftRecoveryService.restoreInterruptedOperationDraft(layout, project);
        recordTiming(timing, VersionSaveTiming.RESTORE_INTERRUPTED_DRAFT, sectionStartedAt);

        sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.PREPARING, 0, 0, "Isolating pending changes");
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

        // Keep a durable fallback until the async save fully commits, without exposing it to live capture.
        sectionStartedAt = System.nanoTime();
        progressSink.update(OperationStage.WRITING, 0, draft.totalChangeCount(), "Writing operation draft");
        this.recoveryRepository.saveOperationDraft(layout, draft);
        recordTiming(timing, VersionSaveTiming.OPERATION_DRAFT_WRITE, sectionStartedAt);
        LumiTestFailpoints.hit(LumiTestFailpoints.AFTER_OPERATION_DRAFT_WRITE);

        sectionStartedAt = System.nanoTime();
        this.recoveryRepository.deleteDraft(layout);
        recordTiming(timing, VersionSaveTiming.RECOVERY_DRAFT_DELETE, sectionStartedAt);
        return new IsolatedDraft(draft);
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
                null
        );
    }

    ProjectVersion stagePartialRestoreVersion(
            ProjectLayout layout,
            BuildProject project,
            RecoveryDraft draft,
            String message,
            String author,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        return this.writeVersion(
                null,
                layout,
                project,
                draft,
                message,
                author,
                VersionKind.PARTIAL_RESTORE,
                false,
                "",
                progressSink,
                false,
                false,
                null
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
            VersionSaveTimingBuilder timing
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
                timing
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
            VersionSaveTimingBuilder timing
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

        progressSink.update(OperationStage.PREPARING, 0, draft.totalChangeCount(), "Preparing version payload");
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

        String snapshotId = "";
        boolean createSnapshot;
        sectionStartedAt = System.nanoTime();
        try (var ignored = LumaLoadLog.measure("save", "VersionService.snapshotPolicy", "project=" + project.name())) {
            createSnapshot = allowSnapshotCapture
                    && ((parentVersionId == null || parentVersionId.isBlank())
                    || this.snapshotPlanner.shouldCreateSnapshot(project, layout, versions, activeVariant, draft, stats, versionKind));
        } finally {
            recordTiming(timing, VersionSaveTiming.SNAPSHOT_POLICY, sectionStartedAt);
        }
        LumaMod.LOGGER.info(
                "Snapshot policy for version {} in project {} resolved to {}",
                versionId,
                project.name(),
                createSnapshot
        );
        if (createSnapshot) {
            snapshotId = ProjectService.snapshotId(nextIndex);
            progressSink.update(OperationStage.WRITING, draft.changes().size(), draft.changes().size(), "Capturing snapshot");
            List<ChunkPoint> snapshotChunks;
            sectionStartedAt = System.nanoTime();
            try (var ignored = LumaLoadLog.measure("save", "VersionService.collectSnapshotChunks", "project=" + project.name())) {
                snapshotChunks = this.snapshotPlanner.collectSnapshotChunks(layout, project, versions, draft);
            } finally {
                recordTiming(timing, VersionSaveTiming.SNAPSHOT_PREPARATION, sectionStartedAt);
            }
            LumaDebugLog.log(
                    project,
                    "save",
                    "Capturing snapshot {} for version {} across {} tracked chunks",
                    snapshotId,
                    versionId,
                    snapshotChunks.size()
            );
            sectionStartedAt = System.nanoTime();
            try (var ignored = LumaLoadLog.measure(
                    "save",
                    "SnapshotCaptureService.capture",
                    "project=" + project.name() + ", chunks=" + snapshotChunks.size()
            )) {
                this.snapshotCaptureService.capture(
                        layout,
                        project.id().toString(),
                        snapshotId,
                        snapshotChunks,
                        level,
                        now
                );
            } finally {
                recordTiming(timing, VersionSaveTiming.SNAPSHOT_CAPTURE, sectionStartedAt);
            }
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
                "{} version {} for project {} with snapshot={} and patch={}",
                publishHead ? "Committed" : "Staged",
                version.id(),
                project.name(),
                version.snapshotId(),
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

    void publishStagedVersion(
            ServerLevel level,
            ProjectLayout layout,
            BuildProject project,
            ProjectVersion version,
            RecoveryDraft draftForPreview,
            boolean schedulePreview
    ) throws IOException {
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(version.variantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Variant is missing for staged version " + version.variantId()));
        this.publishVersionMetadata(layout, project, variants, activeVariant, version, Instant.now());
        if (level != null) {
            HistoryCaptureManager.getInstance().invalidateProjectCache(level.getServer());
        }
        if (schedulePreview && project.settings().previewGenerationEnabled() && level != null) {
            List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
            Bounds3i bounds = this.previewBoundsResolver.resolve(layout, project, versions, version, draftForPreview, level);
            this.previewCaptureRequestService.queue(layout, version.id(), project.dimensionId(), bounds);
        }
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
                    timing
            );
            long sectionStartedAt = System.nanoTime();
            this.rebaseConsumedWorkingDraft(level, project, rebaseFromVersionId, version.id());
            recordTiming(timing, VersionSaveTiming.REBASE_WORKING_DRAFT, sectionStartedAt);
            sectionStartedAt = System.nanoTime();
            this.recoveryRepository.deleteOperationDraft(layout);
            recordTiming(timing, VersionSaveTiming.OPERATION_DRAFT_DELETE, sectionStartedAt);
            return version;
        } finally {
            recordTiming(timing, VersionSaveTiming.BACKGROUND_TOTAL, backgroundStartedAt);
        }
    }

    private void rebaseConsumedWorkingDraft(
            ServerLevel level,
            BuildProject project,
            String previousHeadVersionId,
            String newHeadVersionId
    ) {
        try {
            HistoryCaptureManager.getInstance().rebaseWorkingDraftBase(
                    level.getServer(),
                    project.id().toString(),
                    previousHeadVersionId,
                    newHeadVersionId
            );
        } catch (IOException exception) {
            LumaMod.LOGGER.warn(
                    "Saved version {} for project {}, but failed to rebase the active working draft from {}",
                    newHeadVersionId,
                    project.name(),
                    previousHeadVersionId,
                    exception
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

    int versionsSinceSnapshot(List<ProjectVersion> versions, String headVersionId) {
        return this.snapshotPlanner.versionsSinceSnapshot(versions, headVersionId);
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
            case MERGE -> "Merged branches";
            case AUTO_CHECKPOINT -> "Auto checkpoint";
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
            case MERGE -> "Saved branch merge as a new version";
            case AUTO_CHECKPOINT -> "Saved automatic checkpoint before a large edit";
            case INITIAL, MANUAL -> "Saved version from tracked changes";
        };
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

    private record IsolatedDraft(RecoveryDraft draft) {
    }

}
