package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides whether secondary world fallout belongs to an active capture session.
 */
final class ActiveSessionRegionPolicy {

    boolean contains(ServerLevel level, CaptureSessionState session, ChunkPoint chunk) {
        return this.contains(session, chunk, false);
    }

    boolean contains(CaptureSessionState session, ChunkPoint chunk, boolean ignoredPlayerLoadedChunk) {
        if (session == null || chunk == null) {
            return false;
        }
        return session.isWithinStabilizationEnvelope(chunk);
    }
}
