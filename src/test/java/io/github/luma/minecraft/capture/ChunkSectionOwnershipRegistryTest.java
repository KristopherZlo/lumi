package io.github.luma.minecraft.capture;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkSectionOwnershipRegistryTest {

    @Test
    void convertsLocalSectionCoordinatesToWorldBlockPosition() {
        ChunkSectionOwnershipRegistry.SectionOwner owner =
                new ChunkSectionOwnershipRegistry.SectionOwner(null, new ChunkPos(2, -3), -4);

        assertEquals(new BlockPos(33, -62, -45), owner.blockPos(1, 2, 3));
    }
}
