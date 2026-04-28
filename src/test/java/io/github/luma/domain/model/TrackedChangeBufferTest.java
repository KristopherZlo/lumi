package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
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

    @Test
    void contentFingerprintIgnoresTimestampOnlyChanges() {
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
        StoredBlockChange change = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        );

        buffer.addChange(change, now);
        int firstFingerprint = buffer.contentFingerprint();
        buffer.replaceChunks(List.of(new ChunkPoint(0, 0)), List.of(change), now.plusSeconds(20));

        assertEquals(firstFingerprint, buffer.contentFingerprint());
    }

    @Test
    void entityChangesKeepFirstOldPayloadAndLastNewPayload() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.AXIOM,
                now
        );

        String entityId = "00000000-0000-0000-0000-000000000001";
        buffer.addEntityChange(new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity("minecraft:block_display", entityId, 1.0D),
                entity("minecraft:block_display", entityId, 2.0D)
        ), now);
        buffer.addEntityChange(new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity("minecraft:block_display", entityId, 2.0D),
                entity("minecraft:block_display", entityId, 3.0D)
        ), now.plusSeconds(1));

        assertEquals(1, buffer.entityChangeCount());
        StoredEntityChange stored = buffer.orderedEntityChanges().getFirst();
        assertEquals(1.0D, stored.oldValue().entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
        assertEquals(3.0D, stored.newValue().entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D));
        assertTrue(buffer.touchesChunk(new ChunkPoint(0, 0)));
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
