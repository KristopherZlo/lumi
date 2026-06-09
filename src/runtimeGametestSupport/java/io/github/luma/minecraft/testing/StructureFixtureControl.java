package io.github.luma.minecraft.testing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * Player-interactable control discovered in a saved structure fixture.
 */
record StructureFixtureControl(BlockPos relativePos, BlockPos pos, Direction face, String label) {

    static List<StructureFixtureControl> findBlueConcreteControls(ServerLevel level, SingleplayerTestVolume volume) {
        List<StructureFixtureControl> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(volume.min(), volume.max())) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            Block block = state.getBlock();
            if (!(block instanceof ButtonBlock) && !(block instanceof LeverBlock)) {
                continue;
            }
            if (!level.getBlockState(supportPos(immutable, state)).is(Blocks.BLUE_CONCRETE)) {
                continue;
            }
            found.add(fromWorld(volume.min(), immutable, state));
        }
        found.sort(Comparator
                .comparingInt((StructureFixtureControl control) -> control.relativePos().getY())
                .thenComparingInt(control -> control.relativePos().getZ())
                .thenComparingInt(control -> control.relativePos().getX()));
        return List.copyOf(found);
    }

    StructureFixtureControl at(BlockPos origin) {
        return new StructureFixtureControl(this.relativePos, origin.offset(this.relativePos), this.face, this.label);
    }

    private static StructureFixtureControl fromWorld(BlockPos origin, BlockPos pos, BlockState state) {
        BlockPos relative = pos.subtract(origin);
        return new StructureFixtureControl(relative, pos, interactionFace(state), label(state, relative));
    }

    private static Direction interactionFace(BlockState state) {
        if (!state.hasProperty(LeverBlock.FACE)) {
            return Direction.UP;
        }

        AttachFace face = state.getValue(LeverBlock.FACE);
        Direction facing = state.hasProperty(LeverBlock.FACING)
                ? state.getValue(LeverBlock.FACING)
                : Direction.NORTH;
        return switch (face) {
            case CEILING -> Direction.DOWN;
            case FLOOR -> Direction.UP;
            case WALL -> facing;
        };
    }

    private static BlockPos supportPos(BlockPos pos, BlockState state) {
        if (!state.hasProperty(LeverBlock.FACE)) {
            return pos.below();
        }

        AttachFace face = state.getValue(LeverBlock.FACE);
        Direction facing = state.hasProperty(LeverBlock.FACING)
                ? state.getValue(LeverBlock.FACING)
                : Direction.NORTH;
        return switch (face) {
            case CEILING -> pos.above();
            case FLOOR -> pos.below();
            case WALL -> pos.relative(facing.getOpposite());
        };
    }

    private static String label(BlockState state, BlockPos relative) {
        return state.getBlock().getName().getString() + " at +"
                + relative.getX() + " +" + relative.getY() + " +" + relative.getZ();
    }
}
