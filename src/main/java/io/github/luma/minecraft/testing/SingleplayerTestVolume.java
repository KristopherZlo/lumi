package io.github.luma.minecraft.testing;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Small empty world volume reserved for the singleplayer runtime test suite.
 */
final class SingleplayerTestVolume {

    static final int WIDTH = 11;
    static final int HEIGHT = 6;
    static final int DEPTH = 11;

    private final BlockPos min;
    private final BlockPos max;

    private SingleplayerTestVolume(BlockPos min) {
        this.min = min.immutable();
        this.max = min.offset(WIDTH - 1, HEIGHT - 1, DEPTH - 1).immutable();
    }

    static Optional<SingleplayerTestVolume> find(ServerLevel level, BlockPos near) {
        int chunkBaseX = Math.floorDiv(near.getX(), 16) << 4;
        int chunkBaseZ = Math.floorDiv(near.getZ(), 16) << 4;
        int originX = chunkBaseX + 5;
        int originZ = chunkBaseZ + 5;
        int minBaseY = Math.max(level.getMinY(), near.getY() + 6);
        int maxBaseY = level.getMaxY() - HEIGHT + 1;

        for (int y = maxBaseY; y >= minBaseY; y--) {
            SingleplayerTestVolume candidate = new SingleplayerTestVolume(new BlockPos(originX, y, originZ));
            if (candidate.isAir(level)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    BlockPos min() {
        return this.min;
    }

    BlockPos max() {
        return this.max;
    }

    BlockPos markerA() {
        return this.min.offset(1, 1, 1);
    }

    BlockPos markerB() {
        return this.min.offset(2, 1, 1);
    }

    BlockPos markerC() {
        return this.min.offset(3, 1, 1);
    }

    BlockPos markerD() {
        return this.min.offset(1, 2, 1);
    }

    boolean isAir(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(this.min, this.max)) {
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }
}
