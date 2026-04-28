package io.github.luma.minecraft.capture;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionOwnershipRegistryTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void convertsLocalSectionCoordinatesToWorldBlockPosition() {
        ChunkSectionOwnershipRegistry.SectionOwner owner =
                new ChunkSectionOwnershipRegistry.SectionOwner(null, new ChunkPos(2, -3), -4);

        assertEquals(new BlockPos(33, -62, -45), owner.blockPos(1, 2, 3));
    }

    @Test
    void matchesExistingOwnerByLevelIdentityChunkAndSection() {
        ChunkSectionOwnershipRegistry.SectionOwner owner =
                new ChunkSectionOwnershipRegistry.SectionOwner(null, new ChunkPos(2, -3), -4);

        assertTrue(owner.matches(null, new ChunkPos(2, -3), -4));
        assertFalse(owner.matches(null, new ChunkPos(2, -3), -3));
        assertFalse(owner.matches(null, new ChunkPos(3, -3), -4));
    }
}
