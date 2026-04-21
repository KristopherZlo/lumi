package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedChangeBufferTest {

    @Test
    void collapseKeepsFirstOldStateAndLastNewState() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                now
        );

        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ), now);
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:dirt"),
                payload("minecraft:gold_block")
        ), now.plusSeconds(1));

        assertEquals(1, buffer.size());
        StoredBlockChange stored = buffer.orderedChanges().getFirst();
        assertEquals("minecraft:stone", stored.oldValue().blockId());
        assertEquals("minecraft:gold_block", stored.newValue().blockId());
    }

    @Test
    void collapseRemovesNoOpReverts() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                now
        );

        buffer.addChange(new StoredBlockChange(
                new BlockPoint(2, 70, 2),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ), now);
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(2, 70, 2),
                payload("minecraft:dirt"),
                payload("minecraft:stone")
        ), now.plusSeconds(1));

        assertTrue(buffer.isEmpty());
    }

    @Test
    void replaceChunksRebuildsOnlySelectedChunk() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                now
        );

        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ), now);
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(18, 64, 1),
                payload("minecraft:stone"),
                payload("minecraft:gold_block")
        ), now.plusSeconds(1));

        buffer.replaceChunks(
                List.of(new ChunkPoint(0, 0)),
                List.of(new StoredBlockChange(
                        new BlockPoint(2, 64, 2),
                        payload("minecraft:stone"),
                        payload("minecraft:diamond_block")
                )),
                now.plusSeconds(2)
        );

        assertEquals(2, buffer.size());
        assertFalse(buffer.touchesChunk(new ChunkPoint(0, 1)));
        assertTrue(buffer.touchesChunk(new ChunkPoint(0, 0)));
        assertTrue(buffer.touchesChunk(new ChunkPoint(1, 0)));
        assertEquals(List.of(new ChunkPoint(0, 0), new ChunkPoint(1, 0)), buffer.touchedChunks());
        assertEquals(Set.of("minecraft:diamond_block", "minecraft:gold_block"), buffer.orderedChanges().stream()
                .map(change -> change.newValue().blockId())
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static StatePayload payload(String blockId) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
