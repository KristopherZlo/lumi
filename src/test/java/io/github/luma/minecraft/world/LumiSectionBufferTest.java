package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LumiSectionBufferTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void maskUsesMinecraftSectionLocalOrder() {
        SectionChangeMask mask = SectionChangeMask.builder()
                .set(2, 3, 4)
                .set(15, 15, 15)
                .build();

        Assertions.assertEquals(2, mask.cardinality());
        Assertions.assertTrue(mask.contains((3 << 8) | (4 << 4) | 2));
        Assertions.assertTrue(mask.contains(4095));
        Assertions.assertEquals(2, SectionChangeMask.localX((3 << 8) | (4 << 4) | 2));
        Assertions.assertEquals(3, SectionChangeMask.localY((3 << 8) | (4 << 4) | 2));
        Assertions.assertEquals(4, SectionChangeMask.localZ((3 << 8) | (4 << 4) | 2));
    }

    @Test
    void bufferConvertsChangedCellsToWorldPlacements() {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(4)
                .set(1, 2, 3, Blocks.CHEST.defaultBlockState(), blockEntity)
                .build();

        List<PreparedBlockPlacement> placements = buffer.toPlacements(new ChunkPoint(2, -1));

        Assertions.assertEquals(1, placements.size());
        PreparedBlockPlacement placement = placements.getFirst();
        Assertions.assertEquals(33, placement.pos().getX());
        Assertions.assertEquals(66, placement.pos().getY());
        Assertions.assertEquals(-13, placement.pos().getZ());
        Assertions.assertEquals(Blocks.CHEST.defaultBlockState(), placement.state());
        Assertions.assertEquals("minecraft:chest", placement.blockEntityTag().getStringOr("id", ""));
    }

    @Test
    void classifierChoosesRewriteForFullOrVeryDenseSections() {
        SectionApplySafetyClassifier classifier = new SectionApplySafetyClassifier();
        LumiSectionBuffer.Builder sparse = LumiSectionBuffer.builder(0);
        sparse.set(0, Blocks.STONE.defaultBlockState(), null);

        LumiSectionBuffer.Builder dense = LumiSectionBuffer.builder(0);
        for (int index = 0; index < SectionApplySafetyClassifier.NATIVE_DENSE_THRESHOLD; index++) {
            dense.set(index, Blocks.STONE.defaultBlockState(), null);
        }

        LumiSectionBuffer.Builder rewrite = LumiSectionBuffer.builder(0);
        for (int index = 0; index < SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD; index++) {
            rewrite.set(index, Blocks.STONE.defaultBlockState(), null);
        }

        Assertions.assertEquals(SectionApplyPath.DIRECT_SECTION, classifier.classify(sparse.build(), false).path());
        Assertions.assertEquals(SectionApplyPath.SECTION_REWRITE, classifier.classify(sparse.build(), true).path());
        Assertions.assertEquals(SectionApplyPath.SECTION_NATIVE, classifier.classify(dense.build(), false).path());
        Assertions.assertEquals(SectionApplyPath.SECTION_REWRITE, classifier.classify(rewrite.build(), false).path());
    }

    @Test
    void classifierChoosesRewriteForDenseSingleLayerSections() {
        SectionApplySafetyClassifier classifier = new SectionApplySafetyClassifier();
        LumiSectionBuffer.Builder layer = LumiSectionBuffer.builder(0);
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                layer.set(localX, 0, localZ, Blocks.STONE.defaultBlockState(), null);
            }
        }

        Assertions.assertEquals(SectionApplyPath.SECTION_REWRITE, classifier.classify(layer.build(), false).path());
    }
}
