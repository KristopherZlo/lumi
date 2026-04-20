package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
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
import io.github.luma.domain.model.StoredBlockChange;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.minecraft.server.level.ServerLevel;

/**
 * Saves tracked edits as durable project versions.
 *
 * <p>The service consumes the live tracked buffer, writes the patch-first v3
 * history payloads, applies snapshot policy, finalizes version manifests, and
 * schedules optional preview generation outside the critical durability path.
 */
public final class VersionService {

    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(new PreviewThreadFactory());

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final PreviewService previewService = new PreviewService();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle startSaveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        return this.startSaveVersion(level, projectName, message, author, VersionKind.MANUAL);
    }

    /**
     * Starts an asynchronous save operation for the current tracked changes.
     *
     * <p>The durable version manifest is only written after the background
     * operation completes successfully. Until then, the current draft is kept in
     * recovery storage as a fallback.
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
        Optional<RecoveryDraft> persistedDraft = this.recoveryRepository.loadDraft(layout);
        RecoveryDraft draft = HistoryCaptureManager.getInstance()
                .consumeSession(level.getServer(), project.id().toString())
                .map(TrackedChangeBuffer::toDraft)
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

        // Keep a durable fallback until the async save fully commits.
        this.recoveryRepository.saveDraft(layout, draft);

        return this.worldOperationManager.startBackgroundOperation(
                level,
                project.id().toString(),
                "save-version",
                "blocks",
                progressSink -> this.writeVersion(level, layout, project, draft, message, author, versionKind, true, progressSink)
        );
    }

    public ProjectVersion refreshPreview(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        ProjectVersion version = this.versionRepository.load(layout, versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        PreviewInfo preview = project.tracksWholeDimension()
                ? PreviewInfo.none()
                : this.previewService.capture(layout, versionId, project.bounds(), level);
        ProjectVersion updated = new ProjectVersion(
                version.id(),
                version.projectId(),
                version.variantId(),
                version.parentVersionId(),
                version.snapshotId(),
                version.patchIds(),
                version.versionKind(),
                version.author(),
                version.message(),
                version.stats(),
                preview,
                version.sourceInfo(),
                version.createdAt()
        );
        this.versionRepository.save(layout, updated);
        return updated;
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
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant activeVariant = variants.stream()
                .filter(variant -> variant.id().equals(draft.variantId()))
                .findFirst()
                .orElseGet(() -> variants.stream()
                        .filter(variant -> variant.id().equals(project.activeVariantId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Active variant is missing for " + project.name())));

        int nextIndex = versions.size() + 1;
        Instant now = Instant.now();
        String versionId = ProjectService.versionId(nextIndex);
        String patchId = ProjectService.patchId(nextIndex);
        ChangeStats stats = ChangeStatsFactory.summarize(draft.changes());
        LumaMod.LOGGER.info(
                "Preparing version {} for project {}: {} blocks across {} chunks",
                versionId,
                project.name(),
                stats.changedBlocks(),
                stats.changedChunks()
        );

        progressSink.update(OperationStage.PREPARING, 0, draft.changes().size(), "Preparing version payload");
        var patchMetadata = this.patchDataRepository.writePayload(
                layout,
                patchId,
                project.id().toString(),
                versionId,
                draft.changes()
        );
        progressSink.update(OperationStage.WRITING, draft.changes().size(), draft.changes().size(), "Writing patch index");
        this.patchMetaRepository.save(layout, patchMetadata);

        String snapshotId = "";
        boolean createSnapshot = this.shouldCreateSnapshot(project, layout, versions, activeVariant, draft, stats, versionKind);
        LumaMod.LOGGER.info(
                "Snapshot policy for version {} in project {} resolved to {}",
                versionId,
                project.name(),
                createSnapshot
        );
        if (createSnapshot) {
            snapshotId = ProjectService.snapshotId(nextIndex);
            progressSink.update(OperationStage.WRITING, draft.changes().size(), draft.changes().size(), "Capturing snapshot");
            this.snapshotWriter.capture(
                    layout,
                    project.id().toString(),
                    snapshotId,
                    this.collectSnapshotChunks(layout, project, versions, draft),
                    level,
                    now
            );
        }

        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                activeVariant.id(),
                activeVariant.headVersionId(),
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
        this.recoveryRepository.deleteDraft(layout);
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

        if (schedulePreview && project.settings().previewGenerationEnabled() && !project.tracksWholeDimension()) {
            CompletableFuture.runAsync(() -> this.tryRefreshPreview(layout, project, version, level), PREVIEW_EXECUTOR);
        }

        return version;
    }

    private void tryRefreshPreview(ProjectLayout layout, BuildProject project, ProjectVersion version, ServerLevel level) {
        try {
            LumaMod.LOGGER.info("Starting async preview refresh for version {} in project {}", version.id(), project.name());
            PreviewInfo preview = this.previewService.capture(layout, version.id(), project.bounds(), level);
            this.versionRepository.save(layout, new ProjectVersion(
                    version.id(),
                    version.projectId(),
                    version.variantId(),
                    version.parentVersionId(),
                    version.snapshotId(),
                    version.patchIds(),
                    version.versionKind(),
                    version.author(),
                    version.message(),
                    version.stats(),
                    preview,
                    version.sourceInfo(),
                    version.createdAt()
            ));
            LumaMod.LOGGER.info("Completed async preview refresh for version {} in project {}", version.id(), project.name());
        } catch (Exception ignored) {
            LumaMod.LOGGER.warn("Preview refresh failed for version {} in project {}", version.id(), project.name(), ignored);
        }
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
        if (effectiveKind == VersionKind.INITIAL || effectiveKind == VersionKind.LEGACY || effectiveKind == VersionKind.RESTORE) {
            return true;
        }
        if (versions.isEmpty()) {
            return true;
        }
        if (this.versionsSinceSnapshot(versions, activeVariant.headVersionId()) >= project.settings().snapshotEveryVersions()) {
            return true;
        }
        return this.exceedsSnapshotVolumeThreshold(project, layout, draft, stats);
    }

    private int versionsSinceSnapshot(List<ProjectVersion> versions, String headVersionId) {
        Map<String, ProjectVersion> versionMap = new HashMap<>();
        for (ProjectVersion version : versions) {
            versionMap.put(version.id(), version);
        }

        int count = 0;
        ProjectVersion cursor = headVersionId == null || headVersionId.isBlank() ? null : versionMap.get(headVersionId);
        while (cursor != null) {
            if (cursor.snapshotId() != null && !cursor.snapshotId().isBlank()) {
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
            return (double) stats.changedBlocks() / (double) volume >= threshold;
        }

        List<ChunkPoint> knownChunks = new ArrayList<>(this.baselineChunkRepository.listChunks(layout));
        knownChunks = ChunkSelectionFactory.merge(knownChunks, ChunkSelectionFactory.fromStoredChanges(draft.changes()));
        int knownChunkCount = Math.max(1, knownChunks.size());
        int changedChunkCount = ChunkSelectionFactory.fromStoredChanges(draft.changes()).size();
        return (double) changedChunkCount / (double) knownChunkCount >= threshold;
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

        return ChunkSelectionFactory.merge(chunks, ChunkSelectionFactory.fromStoredChanges(draft.changes()));
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
            case INITIAL, MANUAL -> "Saved version";
        };
    }

    private ExternalSourceInfo resolveSourceInfo(VersionKind versionKind) {
        return switch (versionKind) {
            case WORLD_ROOT -> ExternalSourceInfo.manual();
            case RECOVERY -> ExternalSourceInfo.recovery();
            case RESTORE -> ExternalSourceInfo.restore();
            case INITIAL, MANUAL, LEGACY -> ExternalSourceInfo.manual();
        };
    }

    private String resolveJournalMessage(VersionKind versionKind) {
        return switch (versionKind) {
            case WORLD_ROOT -> "Created workspace root version";
            case RECOVERY -> "Saved recovery draft as a new version";
            case LEGACY -> "Saved a new version while migrating a legacy snapshot project";
            case RESTORE -> "Saved restore checkpoint version";
            case INITIAL, MANUAL -> "Saved version from tracked changes";
        };
    }

    private static final class PreviewThreadFactory implements ThreadFactory {

        private int nextIndex = 1;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Lumi-Preview-" + this.nextIndex++);
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    }
}
