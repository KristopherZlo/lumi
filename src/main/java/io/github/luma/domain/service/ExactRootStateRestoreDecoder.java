package io.github.luma.domain.service;

import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.SnapshotBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

final class ExactRootStateRestoreDecoder {

    private final BaselineChunkRepository baselineChunkRepository;
    private final SnapshotReader snapshotReader;
    private final RestoreChunkCollector chunkCollector;
    private final SnapshotBatchPreparer snapshotBatchPreparer;

    ExactRootStateRestoreDecoder(
            BaselineChunkRepository baselineChunkRepository,
            SnapshotReader snapshotReader,
            RestoreChunkCollector chunkCollector,
            SnapshotBatchPreparer snapshotBatchPreparer
    ) {
        this.baselineChunkRepository = baselineChunkRepository;
        this.snapshotReader = snapshotReader;
        this.chunkCollector = chunkCollector;
        this.snapshotBatchPreparer = snapshotBatchPreparer;
    }

    DecodedExactRootState decode(
            ProjectLayout layout,
            ServerLevel level,
            ProjectVersion targetVersion,
            RestoreService.ExactRootStateRestorePlan plan,
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
            return this.skip(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state; no changed block positions"
            );
        }
        List<BlockPoint> selectedPositions = this.filterPositions(layout, targetVersion, positions);
        if (selectedPositions.isEmpty()) {
            return this.skip(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state; no tracked baseline positions"
            );
        }

        if (targetVersion.versionKind() == VersionKind.INITIAL) {
            batches.addAll(this.snapshotBatchPreparer.preparePositions(
                    this.snapshotReader.readFile(
                            layout.snapshotFile(targetVersion.snapshotId()),
                            this.chunkCollector.chunksForPositions(selectedPositions)
                    ),
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
            return this.skip(
                    batches,
                    completedSources,
                    totalSources,
                    progressSink,
                    "Skipped exact root state for unsupported target kind"
            );
        }

        List<PreparedChunkBatch> prepared = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<BlockPoint>> entry : this.chunkCollector.positionsByChunk(selectedPositions).entrySet()) {
            ChunkPoint chunk = entry.getKey();
            try (var ignored = LumaLoadLog.measure(
                    "restore",
                    "ExactRootStateRestoreDecoder.baselineChunk",
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

    private DecodedExactRootState skip(
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

    private List<BlockPoint> filterPositions(
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

    record DecodedExactRootState(List<PreparedChunkBatch> batches, int completedSources) {

        DecodedExactRootState {
            batches = batches == null ? List.of() : List.copyOf(batches);
        }
    }
}
