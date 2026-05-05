package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Decides whether secondary world fallout belongs to an active capture session.
 */
final class ActiveSessionRegionPolicy {

    boolean contains(ServerLevel level, CaptureSessionState session, ChunkPoint chunk) {
        return this.contains(session, chunk, this.hasPlayerWatchingChunk(level, chunk));
    }

    boolean contains(CaptureSessionState session, ChunkPoint chunk, boolean playerLoadedChunk) {
        if (session == null || chunk == null) {
            return false;
        }
        return session.isWithinStabilizationEnvelope(chunk) || playerLoadedChunk;
    }

    private boolean hasPlayerWatchingChunk(ServerLevel level, ChunkPoint chunk) {
        if (level == null || chunk == null) {
            return false;
        }
        if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
            return false;
        }
        return !level.getChunkSource().chunkMap
                .getPlayers(new ChunkPos(chunk.x(), chunk.z()), false)
                .isEmpty();
    }
}
