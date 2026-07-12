package io.github.luma.domain.service;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.SnapshotBatchPreparer;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Decodes the tracked baseline scope used by a world-root restore. */
final class WorldRootRestorePreparer {

    private final BaselineChunkRepository baselineChunks;
    private final SnapshotReader snapshotReader;
    private final SnapshotBatchPreparer batchPreparer;
    private final RestoreBatchCollapser batchCollapser;

    WorldRootRestorePreparer(
            BaselineChunkRepository baselineChunks,
            SnapshotReader snapshotReader,
            SnapshotBatchPreparer batchPreparer,
            RestoreBatchCollapser batchCollapser
    ) {
        this.baselineChunks = baselineChunks;
        this.snapshotReader = snapshotReader;
        this.batchPreparer = batchPreparer;
        this.batchCollapser = batchCollapser;
    }

    List<PreparedChunkBatch> prepare(
            ProjectLayout layout,
            BuildProject project,
            ServerLevel level,
            RestoreEntityTypeSelection entitySelection,
            WorldOperationManager.ProgressSink progressSink
    ) throws IOException {
        List<ChunkPoint> trackedChunks = this.baselineChunks.listChunks(layout);
        if (trackedChunks.isEmpty()) {
            throw new IllegalArgumentException("Missing baseline chunks for world-root restore: no tracked baseline chunks");
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        int index = 0;
        for (ChunkPoint chunk : trackedChunks) {
            try (var ignored = LumaLoadLog.measure(
                    "restore", "WorldRootRestorePreparer.baselineChunk",
                    "chunk=" + chunk.x() + ":" + chunk.z()
            )) {
                batches.addAll(this.batchPreparer.prepare(
                        this.snapshotReader.readFile(this.baselineChunks.filePath(layout, chunk)),
                        level,
                        entitySelection.excludedEntityTypes()
                ));
            }
            progressSink.update(OperationStage.PREPARING, ++index, trackedChunks.size(),
                    "Decoded world root chunk " + chunk.x() + ":" + chunk.z());
        }

        List<PreparedChunkBatch> collapsed = this.batchCollapser.collapse("world-root", batches);
        LumaMod.LOGGER.info("Decoded {} tracked baseline chunks for world root restore in project {}",
                trackedChunks.size(), project.name());
        LumaDebugLog.log(project, "restore",
                "World root restore decoded {} tracked chunks into {} chunk batches",
                trackedChunks.size(), collapsed.size());
        return collapsed;
    }
}
