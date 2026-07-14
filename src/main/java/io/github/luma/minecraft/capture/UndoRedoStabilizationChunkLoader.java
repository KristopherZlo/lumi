package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Ensures deferred stabilization chunks are present before live action selection. */
final class UndoRedoStabilizationChunkLoader {

    void load(ServerLevel level, BuildProject project, CaptureSessionState session) {
        if (level == null || project == null || session == null) {
            return;
        }
        List<ChunkPoint> pendingChunks = session.pendingReconcileChunks();
        if (pendingChunks.isEmpty()) {
            return;
        }

        int loaded = 0;
        int alreadyLoaded = 0;
        for (ChunkPoint chunk : pendingChunks) {
            if (chunk == null) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                alreadyLoaded += 1;
                continue;
            }
            if (level.getChunk(chunk.x(), chunk.z()) != null) {
                loaded += 1;
            }
        }
        if (loaded > 0) {
            LumaMod.LOGGER.info(
                    "Loaded {} deferred stabilization chunks for undo/redo in project {} ({} already loaded)",
                    loaded,
                    project.name(),
                    alreadyLoaded
            );
        }
        LumaDebugLog.log(
                project,
                "capture",
                "Undo/redo stabilization chunk load pending={} loaded={} alreadyLoaded={}",
                pendingChunks.size(),
                loaded,
                alreadyLoaded
        );
    }
}
