package io.github.luma.client.selection;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the first block under the crosshair without causing client chunk loads.
 */
final class LoadedChunkBlockRaycaster {

    private static final double MIN_RANGE = 64.0D;
    private static final double MAX_RANGE = 512.0D;

    Optional<BlockPos> findTargetBlock(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return Optional.empty();
        }

        Vec3 eye = client.player.getEyePosition(1.0F);
        Vec3 direction = client.player.getViewVector(1.0F).normalize();
        return this.findTargetBlock(client.level, eye, direction, this.selectionRange(client));
    }

    Optional<BlockPos> findTargetBlock(ClientLevel level, Vec3 start, Vec3 direction, double maxDistance) {
        if (level == null || start == null || direction == null || maxDistance <= 0.0D) {
            return Optional.empty();
        }

        int x = Mth.floor(start.x);
        int y = Mth.floor(start.y);
        int z = Mth.floor(start.z);
        int stepX = direction.x > 0.0D ? 1 : -1;
        int stepY = direction.y > 0.0D ? 1 : -1;
        int stepZ = direction.z > 0.0D ? 1 : -1;
        double tMaxX = this.initialBoundaryDistance(start.x, direction.x, x, stepX);
        double tMaxY = this.initialBoundaryDistance(start.y, direction.y, y, stepY);
        double tMaxZ = this.initialBoundaryDistance(start.z, direction.z, z, stepZ);
        double tDeltaX = this.stepDistance(direction.x);
        double tDeltaY = this.stepDistance(direction.y);
        double tDeltaZ = this.stepDistance(direction.z);
        double travelled = 0.0D;

        while (travelled <= maxDistance) {
            BlockPos pos = new BlockPos(x, y, z);
            if (this.isInsideBuildHeight(level, y) && this.loadedBlockState(level, pos).filter(state -> !state.isAir()).isPresent()) {
                return Optional.of(pos);
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                travelled = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                travelled = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                travelled = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return Optional.empty();
    }

    private Optional<BlockState> loadedBlockState(ClientLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())
        );
        return chunk == null ? Optional.empty() : Optional.of(chunk.getBlockState(pos));
    }

    private boolean isInsideBuildHeight(ClientLevel level, int y) {
        return y >= level.getMinY() && y < level.getMaxY();
    }

    private double selectionRange(Minecraft client) {
        int renderDistance = client.options == null ? 0 : client.options.renderDistance().get();
        return Math.min(MAX_RANGE, Math.max(MIN_RANGE, renderDistance * 16.0D));
    }

    private double initialBoundaryDistance(double start, double direction, int block, int step) {
        if (direction == 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? block + 1.0D : block;
        return Math.max(0.0D, (boundary - start) / direction);
    }

    private double stepDistance(double direction) {
        return direction == 0.0D ? Double.POSITIVE_INFINITY : Math.abs(1.0D / direction);
    }
}
