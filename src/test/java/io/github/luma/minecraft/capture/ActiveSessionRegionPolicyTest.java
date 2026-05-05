package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveSessionRegionPolicyTest {

    private final ActiveSessionRegionPolicy policy = new ActiveSessionRegionPolicy();

    @Test
    void activeRegionIncludesCausalEnvelopeAndPlayerLoadedChunks() {
        CaptureSessionState session = CaptureSessionState.create(buffer());
        session.addRootChunk(new ChunkPoint(10, -4));

        assertTrue(this.policy.contains(session, new ChunkPoint(11, -3), false));
        assertTrue(this.policy.contains(session, new ChunkPoint(20, -4), true));
        assertFalse(this.policy.contains(session, new ChunkPoint(20, -4), false));
        assertFalse(this.policy.contains(null, new ChunkPoint(20, -4), true));
    }

    private static TrackedChangeBuffer buffer() {
        return TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-20T10:15:30Z")
        );
    }
}
