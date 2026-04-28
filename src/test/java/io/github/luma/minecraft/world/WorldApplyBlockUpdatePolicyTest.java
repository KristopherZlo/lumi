package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyBlockUpdatePolicyTest {

    private final WorldApplyBlockUpdatePolicy policy = new WorldApplyBlockUpdatePolicy();

    @Test
    void persistentApplyRefreshesClientsWithoutNeighborUpdates() {
        int flags = this.policy.placementFlags(Blocks.TNT.defaultBlockState());

        Assertions.assertEquals(Block.UPDATE_CLIENTS, flags & Block.UPDATE_CLIENTS);
        Assertions.assertEquals(0, flags & Block.UPDATE_NEIGHBORS);
        Assertions.assertEquals(0, flags & Block.UPDATE_MOVE_BY_PISTON);
    }

    @Test
    void persistentApplySkipsPlacementAndShapeSideEffects() {
        int flags = this.policy.placementFlags(Blocks.REDSTONE_BLOCK.defaultBlockState());

        Assertions.assertEquals(Block.UPDATE_KNOWN_SHAPE, flags & Block.UPDATE_KNOWN_SHAPE);
        Assertions.assertEquals(Block.UPDATE_SUPPRESS_DROPS, flags & Block.UPDATE_SUPPRESS_DROPS);
        Assertions.assertEquals(
                Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS,
                flags & Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
        );
        Assertions.assertEquals(Block.UPDATE_SKIP_ON_PLACE, flags & Block.UPDATE_SKIP_ON_PLACE);
    }
}
