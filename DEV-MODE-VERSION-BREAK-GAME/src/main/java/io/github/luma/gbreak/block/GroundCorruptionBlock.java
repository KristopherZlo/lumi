package io.github.luma.gbreak.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;

public final class GroundCorruptionBlock extends Block {

    public static final IntProperty LEAK = IntProperty.of("leak", 0, 3);

    private static final int[] LIGHT_LEVELS = {3, 6, 10, 14};

    public GroundCorruptionBlock(Settings settings) {
        super(settings.luminance(GroundCorruptionBlock::luminance));
        this.setDefaultState(this.getDefaultState().with(LEAK, 1));
    }

    public BlockState stateForLeakIndex(int leakIndex) {
        return this.getDefaultState().with(LEAK, Math.max(0, Math.min(leakIndex, LIGHT_LEVELS.length - 1)));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LEAK);
    }

    private static int luminance(BlockState state) {
        return LIGHT_LEVELS[state.get(LEAK)];
    }
}
