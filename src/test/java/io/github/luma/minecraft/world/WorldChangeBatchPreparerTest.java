package io.github.luma.minecraft.world;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StoredEntityChange;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldChangeBatchPreparerTest {

    private final WorldChangeBatchPreparer preparer = new WorldChangeBatchPreparer();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preparesEntityOnlyNewValueBatches() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000001";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 32.0D))),
                true
        );

        assertEquals(1, batches.size());
        assertEquals(1, batches.getFirst().entityBatch().entitiesToSpawn().size());
        assertEquals(2, batches.getFirst().chunk().x());
    }

    @Test
    void preparesOldValueEntityBatchesByInvertingChanges() throws Exception {
        String entityId = "00000000-0000-0000-0000-000000000002";

        List<PreparedChunkBatch> batches = this.preparer.prepare(
                null,
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 1.0D))),
                false
        );

        assertEquals(1, batches.size());
        assertEquals(List.of(entityId), batches.getFirst().entityBatch().entityIdsToRemove());
    }

    @Test
    void decodedDenseSectionsUseNativeSectionBatches() {
        List<PreparedBlockPlacement> placements = java.util.stream.IntStream
                .range(0, SectionApplySafetyClassifier.NATIVE_DENSE_THRESHOLD)
                .mapToObj(index -> new PreparedBlockPlacement(
                        new BlockPos(index & 15, 64 + ((index >>> 8) & 15), (index >>> 4) & 15),
                        Blocks.STONE.defaultBlockState(),
                        null
                ))
                .toList();

        PreparedChunkBatch batch = this.preparer.prepareDecodedChunk(new ChunkPoint(0, 0), placements, EntityBatch.empty());

        assertEquals(0, batch.placements().size());
        assertEquals(1, batch.nativeSections().size());
        assertEquals(SectionApplyPath.SECTION_NATIVE, batch.nativeSections().getFirst().safetyProfile().path());
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
