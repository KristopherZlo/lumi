package io.github.luma.domain.service;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class VersionSnapshotPlanner {

    private final BaselineChunkRepository baselineChunkRepository;
    private final PatchMetaRepository patchMetaRepository;

    VersionSnapshotPlanner(
            BaselineChunkRepository baselineChunkRepository,
            PatchMetaRepository patchMetaRepository
    ) {
        this.baselineChunkRepository = baselineChunkRepository;
        this.patchMetaRepository = patchMetaRepository;
    }

    boolean shouldCreateSnapshot(
            BuildProject project,
            ProjectLayout layout,
            List<ProjectVersion> versions,
            ProjectVariant activeVariant,
            RecoveryDraft draft,
            ChangeStats stats,
            VersionKind versionKind
    ) throws IOException {
        if (versionKind == VersionKind.INITIAL) {
            LumaDebugLog.log(
                    project,
                    "save",
                    "Snapshot required for project {} because version kind is {}",
                    project.name(),
                    versionKind
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

    List<ChunkPoint> collectSnapshotChunks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            RecoveryDraft draft
    ) throws IOException {
        if (!project.tracksWholeDimension()) {
            return ChunkSelectionFactory.fromBounds(project.bounds());
        }

        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        this.addChunks(chunks, this.baselineChunkRepository.listChunks(layout));
        for (ProjectVersion version : versions) {
            for (String patchId : version.patchIds()) {
                Optional<io.github.luma.domain.model.PatchMetadata> metadata = this.patchMetaRepository.load(layout, patchId);
                if (metadata.isEmpty()) {
                    continue;
                }
                for (var chunk : metadata.get().chunks()) {
                    this.addChunk(chunks, chunk.chunk());
                }
            }
        }

        if (draft == null || draft.isEmpty()) {
            LumaDebugLog.log(project, "save", "Collected {} snapshot chunks for project {} without working draft", chunks.size(), project.name());
            return List.copyOf(chunks);
        }

        this.addChunks(chunks, ChunkSelectionFactory.fromStoredChanges(draft.changes()));
        this.addChunks(chunks, ChunkSelectionFactory.fromStoredEntityChanges(draft.entityChanges()));
        List<ChunkPoint> merged = List.copyOf(chunks);
        LumaDebugLog.log(
                project,
                "save",
                "Collected {} snapshot chunks for project {} including {} draft changes and {} entity changes",
                merged.size(),
                project.name(),
                draft.changes().size(),
                draft.entityChanges().size()
        );
        return merged;
    }

    List<ChunkPoint> collectEntityCheckpointChunks(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            RecoveryDraft draft,
            List<ChunkPoint> liveEntityChunks
    ) throws IOException {
        if (!project.tracksWholeDimension()) {
            return this.collectSnapshotChunks(layout, project, versions, draft);
        }
        return ChunkSelectionFactory.merge(
                this.collectSnapshotChunks(layout, project, versions, draft),
                liveEntityChunks == null ? List.of() : liveEntityChunks
        );
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
        knownChunks = ChunkSelectionFactory.merge(knownChunks, ChunkSelectionFactory.fromStoredEntityChanges(draft.entityChanges()));
        int knownChunkCount = Math.max(1, knownChunks.size());
        int changedChunkCount = ChunkSelectionFactory.merge(
                ChunkSelectionFactory.fromStoredChanges(draft.changes()),
                ChunkSelectionFactory.fromStoredEntityChanges(draft.entityChanges())
        ).size();
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

    private void addChunks(Set<ChunkPoint> chunks, List<ChunkPoint> source) {
        for (ChunkPoint chunk : source == null ? List.<ChunkPoint>of() : source) {
            this.addChunk(chunks, chunk);
        }
    }

    private void addChunk(Set<ChunkPoint> chunks, ChunkPoint chunk) {
        if (chunk != null) {
            chunks.add(chunk);
        }
    }
}
