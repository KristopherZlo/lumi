package io.github.luma.minecraft.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Small empty world volume reserved for the singleplayer runtime test suite.
 */
final class SingleplayerTestVolume {

    static final int WIDTH = 16;
    static final int HEIGHT = 12;
    static final int DEPTH = 16;
    private static final int SURFACE_VOLUME_MARGIN = 5;

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

    static Optional<SingleplayerTestVolume> findNearSurface(ServerLevel level, BlockPos near) {
        int chunkBaseX = Math.floorDiv(near.getX(), 16) << 4;
        int chunkBaseZ = Math.floorDiv(near.getZ(), 16) << 4;
        int originX = chunkBaseX + SURFACE_VOLUME_MARGIN;
        int originZ = chunkBaseZ + SURFACE_VOLUME_MARGIN;
        int floorY = highestSurfaceY(level, originX, originZ);
        int maxBaseY = level.getMaxY() - HEIGHT + 1;
        if (floorY < level.getMinY() || floorY > maxBaseY) {
            return Optional.empty();
        }
        return Optional.of(new SingleplayerTestVolume(new BlockPos(originX, floorY, originZ)));
    }

    private static int highestSurfaceY(ServerLevel level, int originX, int originZ) {
        int highest = level.getMinY();
        for (int x = originX; x < originX + WIDTH; x++) {
            for (int z = originZ; z < originZ + DEPTH; z++) {
                highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        return highest;
    }

    BlockPos min() {
        return this.min;
    }

    BlockPos max() {
        return this.max;
    }

    AABB bounds() {
        return new AABB(
                this.min.getX(),
                this.min.getY(),
                this.min.getZ(),
                this.max.getX() + 1.0D,
                this.max.getY() + 1.0D,
                this.max.getZ() + 1.0D);
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
        return this.airMismatches(level, 1).isEmpty();
    }

    List<String> airMismatches(ServerLevel level, int limit) {
        List<String> mismatches = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(this.min, this.max)) {
            if (!level.getBlockState(pos).isAir()) {
                mismatches.add(this.describeBlock(level, pos));
                if (mismatches.size() >= limit) {
                    return mismatches;
                }
            }
        }
        this.addEntityMismatches(level, limit, mismatches);
        return mismatches;
    }

    void preparePlayerPlatform(ServerLevel level) {
        for (Entity entity : level.getEntities((Entity) null, this.bounds(), entity -> !(entity instanceof ServerPlayer))) {
            entity.discard();
        }
        for (BlockPos pos : BlockPos.betweenClosed(this.min, this.max)) {
            level.setBlock(
                    pos,
                    pos.getY() == this.min.getY()
                            ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(),
                    3
            );
        }
    }

    boolean isPreparedPlayerPlatform(ServerLevel level) {
        return this.preparedPlayerPlatformMismatches(level, 1).isEmpty();
    }

    List<String> preparedPlayerPlatformMismatches(ServerLevel level, int limit) {
        List<String> mismatches = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(this.min, this.max)) {
            if (pos.getY() == this.min.getY()) {
                if (!level.getBlockState(pos).is(Blocks.SMOOTH_STONE)) {
                    mismatches.add(this.describeBlock(level, pos));
                    if (mismatches.size() >= limit) {
                        return mismatches;
                    }
                }
                continue;
            }
            if (!level.getBlockState(pos).isAir()) {
                mismatches.add(this.describeBlock(level, pos));
                if (mismatches.size() >= limit) {
                    return mismatches;
                }
            }
        }
        this.addEntityMismatches(level, limit, mismatches);
        return mismatches;
    }

    private String describeBlock(ServerLevel level, BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + "=" + level.getBlockState(pos);
    }

    private void addEntityMismatches(ServerLevel level, int limit, List<String> mismatches) {
        for (Entity entity : level.getEntities((Entity) null, this.bounds(), entity -> !(entity instanceof ServerPlayer))) {
            mismatches.add("entity=" + entity.getType() + " at " + entity.blockPosition().toShortString());
            if (mismatches.size() >= limit) {
                return;
            }
        }
    }
}
