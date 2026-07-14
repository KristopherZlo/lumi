package io.github.luma.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class AuthoritativeEntityFinalizerTest {

    @Test
    void reassertsOnlyAuthoritativeBatchesAfterOtherApplyWork() {
        List<String> calls = new ArrayList<>();
        AuthoritativeEntityFinalizer finalizer = new AuthoritativeEntityFinalizer(
                (level, chunk, batch, start, max, metrics, context) -> {
                    calls.add(chunk.x() + ":" + chunk.z() + "@" + start);
                    return Math.min(max, BlockChangeApplier.entityOperationCount(batch) - start);
                }
        );

        finalizer.record(batch(new ChunkPoint(1, 2), EntityBatch.empty()));
        finalizer.record(batch(
                new ChunkPoint(3, 4),
                EntityBatch.replaceEntities(List.of(entity("00000000-0000-0000-0000-000000000001")))
        ));

        assertFalse(finalizer.advance(null, 1, Long.MAX_VALUE, null, null));
        assertEquals(1, finalizer.pendingChunks());
        assertTrue(finalizer.advance(null, 1, Long.MAX_VALUE, null, null));
        assertEquals(List.of("3:4@0", "3:4@1"), calls);
        assertEquals(0, finalizer.pendingChunks());
    }

    @Test
    void keepsTheLatestAuthoritativeTargetForAChunk() {
        List<EntityBatch> applied = new ArrayList<>();
        AuthoritativeEntityFinalizer finalizer = new AuthoritativeEntityFinalizer(
                (level, chunk, batch, start, max, metrics, context) -> {
                    applied.add(batch);
                    return BlockChangeApplier.entityOperationCount(batch) - start;
                }
        );
        ChunkPoint chunk = new ChunkPoint(5, 6);
        finalizer.record(batch(chunk, EntityBatch.replaceEntities(List.of())));
        EntityBatch latest = EntityBatch.replaceEntities(List.of(
                entity("00000000-0000-0000-0000-000000000002")
        ));
        finalizer.record(batch(chunk, latest));

        assertTrue(finalizer.advance(null, 8, Long.MAX_VALUE, null, null));
        assertEquals(List.of(latest), applied);
    }

    private static ChunkBatch batch(ChunkPoint chunk, EntityBatch entities) {
        return ChunkBatch.fromPrepared(new PreparedChunkBatch(chunk, List.of(), entities));
    }

    private static CompoundTag entity(String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:armor_stand");
        tag.putString("UUID", uuid);
        return tag;
    }
}
