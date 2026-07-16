package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BatchedWorldStateCaptureTest {
    @Test
    void capturesOnlyDirtyKeysAcrossDeadlineBoundedCalls() throws Exception {
        SectionKey sectionKey = new SectionKey(0, 2, 0);
        EntityChunkKey entityKey = new EntityChunkKey(0, 0);
        SectionBlob section = airSection();
        EntityChunkBlob entities = entities();
        WorkingIndex working = new WorkingIndex();
        working.markDirty(sectionKey);
        working.markDirty(entityKey);
        var dirty = working.snapshot();
        AtomicLong clock = new AtomicLong();
        List<Object> reads = new ArrayList<>();
        UUID player = UUID.fromString("10000000-0000-0000-0000-000000000001");
        PlayerSpawn spawn = new PlayerSpawn(4, 65, 8, 90.0F, 0.0F, false);
        WorldStateReader reader = new WorldStateReader() {
            @Override public SectionBlob read(SectionKey key) {
                reads.add(key);
                clock.addAndGet(60);
                return section;
            }

            @Override public EntityChunkBlob read(EntityChunkKey key) {
                reads.add(key);
                clock.addAndGet(60);
                return entities;
            }

            @Override public Map<UUID, PlayerSpawn> readPlayerSpawns() {
                reads.add(player);
                return Map.of(player, spawn);
            }
        };
        WorldStateCapture.CaptureSession session =
                new BatchedWorldStateCapture(reader, clock::get).begin(dirty);

        assertFalse(session.captureUntil(50));
        assertEquals(1, reads.size());
        assertTrue(session.captureUntil(110));

        var captured = session.finish();
        assertEquals(Map.of(sectionKey, section), captured.sections());
        assertEquals(Map.of(entityKey, entities), captured.entities());
        assertEquals(Map.of(player, spawn), captured.playerSpawns());
        assertEquals(dirty, captured.generations());
        assertEquals(1, captured.statistics().sections());
        assertEquals(1, captured.statistics().entityChunks());
        assertEquals(SectionBlob.BLOCK_COUNT, captured.statistics().blocks());
        assertEquals(1, captured.statistics().entities());
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")),
                Map.of());
    }

    private static EntityChunkBlob entities() {
        return new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 1), "minecraft:armor_stand", new CanonicalNbt(new byte[] {1}))));
    }
}
