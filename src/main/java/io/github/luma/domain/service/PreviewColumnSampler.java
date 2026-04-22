package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

final class PreviewColumnSampler {

    private static final int HORIZONTAL_PADDING = 1;
    private static final int FLOOR_PADDING = 1;
    private static final int TOP_PADDING = 1;

    PreviewScene sample(Bounds3i candidateBounds, BlockGetter blocks) {
        int width = candidateBounds.sizeX();
        int depth = candidateBounds.sizeZ();
        PreviewColumn[] sampled = new PreviewColumn[width * depth];

        boolean hasBlocks = false;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int worldX = candidateBounds.min().x(); worldX <= candidateBounds.max().x(); worldX++) {
            for (int worldZ = candidateBounds.min().z(); worldZ <= candidateBounds.max().z(); worldZ++) {
                PreviewSurface surface = this.resolveSurface(candidateBounds, blocks, cursor, worldX, worldZ);
                if (surface == null) {
                    continue;
                }

                PreviewColumn column = new PreviewColumn(worldX, worldZ, surface.y(), surface.state());
                sampled[index(width, worldX - candidateBounds.min().x(), worldZ - candidateBounds.min().z())] = column;
                hasBlocks = true;

                minX = Math.min(minX, worldX);
                minY = Math.min(minY, surface.y());
                minZ = Math.min(minZ, worldZ);
                maxX = Math.max(maxX, worldX);
                maxY = Math.max(maxY, surface.y());
                maxZ = Math.max(maxZ, worldZ);
            }
        }

        Bounds3i frameBounds = hasBlocks
                ? this.paddedBounds(candidateBounds, minX, minY, minZ, maxX, maxY, maxZ)
                : candidateBounds;
        return new PreviewScene(frameBounds, this.frameColumns(frameBounds, sampled), frameBounds.sizeX(), frameBounds.sizeZ(), hasBlocks);
    }

    private Bounds3i paddedBounds(
            Bounds3i candidateBounds,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        return new Bounds3i(
                new BlockPoint(
                        Math.max(candidateBounds.min().x(), minX - HORIZONTAL_PADDING),
                        Math.max(candidateBounds.min().y(), minY - FLOOR_PADDING),
                        Math.max(candidateBounds.min().z(), minZ - HORIZONTAL_PADDING)
                ),
                new BlockPoint(
                        Math.min(candidateBounds.max().x(), maxX + HORIZONTAL_PADDING),
                        Math.min(candidateBounds.max().y(), maxY + TOP_PADDING),
                        Math.min(candidateBounds.max().z(), maxZ + HORIZONTAL_PADDING)
                )
        );
    }

    private PreviewColumn[] frameColumns(Bounds3i frameBounds, PreviewColumn[] sampled) {
        PreviewColumn[] framed = new PreviewColumn[frameBounds.sizeX() * frameBounds.sizeZ()];
        for (PreviewColumn column : sampled) {
            if (column == null
                    || column.worldX() < frameBounds.min().x()
                    || column.worldX() > frameBounds.max().x()
                    || column.worldZ() < frameBounds.min().z()
                    || column.worldZ() > frameBounds.max().z()) {
                continue;
            }

            int relativeX = column.worldX() - frameBounds.min().x();
            int relativeZ = column.worldZ() - frameBounds.min().z();
            framed[index(frameBounds.sizeX(), relativeX, relativeZ)] = column;
        }
        return framed;
    }

    private PreviewSurface resolveSurface(
            Bounds3i candidateBounds,
            BlockGetter blocks,
            BlockPos.MutableBlockPos cursor,
            int worldX,
            int worldZ
    ) {
        PreviewSurface fallback = null;
        for (int worldY = candidateBounds.max().y(); worldY >= candidateBounds.min().y(); worldY--) {
            cursor.set(worldX, worldY, worldZ);
            BlockState state = blocks.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }

            PreviewSurface surface = new PreviewSurface(worldY, state);
            if (fallback == null) {
                fallback = surface;
            }
            if (this.isPreferredSurface(state)) {
                return surface;
            }
        }
        return fallback;
    }

    private boolean isPreferredSurface(BlockState state) {
        return state.isSolidRender()
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.CLIMBABLE)
                && !state.is(BlockTags.FLOWERS)
                && !state.is(BlockTags.REPLACEABLE);
    }

    private static int index(int width, int x, int z) {
        return (z * width) + x;
    }
}

record PreviewScene(
        Bounds3i frameBounds,
        PreviewColumn[] columns,
        int width,
        int depth,
        boolean hasBlocks
) {

    PreviewColumn columnAt(int relativeX, int relativeZ) {
        if (relativeX < 0 || relativeX >= this.width || relativeZ < 0 || relativeZ >= this.depth) {
            return null;
        }
        return this.columns[(relativeZ * this.width) + relativeX];
    }

    List<PreviewColumn> presentColumns() {
        List<PreviewColumn> present = new ArrayList<>();
        for (PreviewColumn column : this.columns) {
            if (column != null) {
                present.add(column);
            }
        }
        return List.copyOf(present);
    }
}

record PreviewColumn(
        int worldX,
        int worldZ,
        int topY,
        BlockState topState
) {
}

record PreviewSurface(int y, BlockState state) {
}
