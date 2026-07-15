package io.github.luma.gametest;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Deterministic 100,000-cell world fixture for the history load gate. */
final class HistoryLoadWorldFixture {

    static final int WIDTH = 32;
    static final int HEIGHT = 25;
    static final int DEPTH = 125;
    static final int TOTAL_CHANGES = WIDTH * HEIGHT * DEPTH;
    static final int HALF_CHANGES = TOTAL_CHANGES / 2;
    private static final String AUTHOR = "Lumi history load gate";

    private final String projectName;
    private final String projectId;
    private final BlockPos origin;

    private HistoryLoadWorldFixture(String projectName, String projectId, BlockPos origin) {
        this.projectName = projectName;
        this.projectId = projectId;
        this.origin = origin;
    }

    static HistoryLoadWorldFixture create(ServerLevel level, BlockPos playerPosition) throws IOException {
        var project = new ProjectService().ensureWorldProject(level, AUTHOR);
        int y = level.getMaxY() - HEIGHT - 2;
        BlockPos origin = new BlockPos(
                playerPosition.getX() - WIDTH / 2,
                y,
                playerPosition.getZ() - DEPTH / 2
        );
        return new HistoryLoadWorldFixture(project.name(), project.id().toString(), origin);
    }

    void applyBatch(ServerLevel level, Block target, int start, int count) {
        int end = Math.min(TOTAL_CHANGES, start + count);
        try (WorldMutationContext.SourceFrame ignored = WorldMutationContext.pushPlayerSource(
                WorldMutationSource.PLAYER, AUTHOR, true)) {
            for (int index = start; index < end; index++) {
                level.setBlock(this.position(index), target.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    void assertBatch(ServerLevel level, int state, int start, int count) {
        int end = Math.min(TOTAL_CHANGES, start + count);
        for (int index = start; index < end; index++) {
            Block expected = switch (state) {
                case 0 -> Blocks.STONE;
                case 1 -> index < HALF_CHANGES ? Blocks.GOLD_BLOCK : Blocks.STONE;
                case 2 -> Blocks.DIAMOND_BLOCK;
                default -> throw new IllegalArgumentException("Unknown history load state " + state);
            };
            BlockPos pos = this.position(index);
            if (!level.getBlockState(pos).is(expected)) {
                throw new AssertionError("History load mismatch at " + pos + ": expected "
                        + expected + ", found " + level.getBlockState(pos));
            }
        }
    }

    String projectName() {
        return this.projectName;
    }

    String projectId() {
        return this.projectId;
    }

    private BlockPos position(int index) {
        int x = index % WIDTH;
        int z = (index / WIDTH) % DEPTH;
        int y = index / (WIDTH * DEPTH);
        return this.origin.offset(x, y, z);
    }
}
