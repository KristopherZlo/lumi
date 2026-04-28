package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionRegistryTest {

    @Test
    void opensEnsuresAndClosesSessionState() {
        CaptureSessionRegistry registry = new CaptureSessionRegistry();
        TrackedChangeBuffer buffer = buffer("project-a");
        CaptureSessionState session = CaptureSessionState.create(buffer);

        registry.open("project-a", buffer, session);

        assertSame(buffer, registry.buffer("project-a"));
        assertSame(session, registry.session("project-a"));
        assertSame(session, registry.ensureSession("project-a", buffer));
        assertTrue(registry.hasBuffer("project-a"));
        assertEquals(List.of("project-a"), registry.activeProjectIds());

        registry.markDirty("project-a");
        registry.recordDraftFlush("project-a", Instant.EPOCH, 42);
        registry.close("project-a");

        assertNull(registry.buffer("project-a"));
        assertNull(registry.session("project-a"));
        assertFalse(registry.isDirty("project-a"));
        assertFalse(registry.hasDraftFingerprint("project-a", 42));
        assertFalse(registry.hasBuffer("project-a"));
    }

    @Test
    void tracksPersistedDraftFreshnessByFingerprintAndDirtyState() {
        CaptureSessionRegistry registry = new CaptureSessionRegistry();
        TrackedChangeBuffer buffer = buffer("project-a");
        buffer.addChange(change(), Instant.EPOCH.plusSeconds(1));
        registry.open("project-a", buffer, CaptureSessionState.create(buffer));
        registry.recordDraftFlush("project-a", Instant.EPOCH.plusSeconds(2), buffer.contentFingerprint());

        assertTrue(registry.matchesPersistedDraft("project-a", buffer));

        registry.markDirty("project-a");

        assertFalse(registry.matchesPersistedDraft("project-a", buffer));
    }

    private static TrackedChangeBuffer buffer(String projectId) {
        return TrackedChangeBuffer.create(
                "buffer-" + projectId,
                projectId,
                "variant",
                "base",
                "player",
                WorldMutationSource.PLAYER,
                Instant.EPOCH
        );
    }

    private static StoredBlockChange change() {
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        return new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                StatePayload.air(),
                new StatePayload(stone, null)
        );
    }
}
