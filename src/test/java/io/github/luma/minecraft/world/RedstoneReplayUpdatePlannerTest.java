package io.github.luma.minecraft.world;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneReplayUpdatePlannerTest {

    private final RedstoneReplayUpdatePlanner planner = new RedstoneReplayUpdatePlanner();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void poweredStateChangeRequiresNeighborPropagation() {
        BlockState off = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false);
        BlockState on = off.setValue(LeverBlock.POWERED, true);

        assertTrue(this.planner.requiresPropagation(off, on));
    }

    @Test
    void ordinaryBlockChangeDoesNotRequireNeighborPropagation() {
        assertFalse(this.planner.requiresPropagation(
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState()
        ));
    }

    @Test
    void floorAttachedControlsUpdateTheirSupportingBlock() {
        BlockPos pos = new BlockPos(3, 64, 5);
        BlockState off = Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, false);
        BlockState on = off.setValue(LeverBlock.POWERED, true);

        Set<BlockPos> positions = this.planner.updatePositions(pos, off, on);

        assertTrue(positions.contains(pos));
        assertTrue(positions.contains(pos.below()));
    }
}
