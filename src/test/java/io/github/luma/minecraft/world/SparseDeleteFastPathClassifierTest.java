package io.github.luma.minecraft.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SparseDeleteFastPathClassifierTest {

    private final SparseDeleteFastPathClassifier classifier = new SparseDeleteFastPathClassifier();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsPlainDeleteToAir() {
        Assertions.assertTrue(this.classifier.canDelete(
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null
        ));
    }

    @Test
    void rejectsNonAirTargetsAndTargetBlockEntities() {
        Assertions.assertFalse(this.classifier.canDelete(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
                null
        ));
        Assertions.assertFalse(this.classifier.canDelete(
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                new CompoundTag()
        ));
    }

    @Test
    void rejectsCurrentBlockEntitiesAndPoiStates() {
        Assertions.assertFalse(this.classifier.canDelete(
                Blocks.BARREL.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null
        ));
        Assertions.assertFalse(this.classifier.canDelete(
                Blocks.BELL.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null
        ));
    }
}
