package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

final class PreviewColumnSampler {

    private static final int HORIZONTAL_PADDING = 1;
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
                int topY = Integer.MIN_VALUE;
                BlockState topState = null;
                for (int worldY = candidateBounds.max().y(); worldY >= candidateBounds.min().y(); worldY--) {
                    cursor.set(worldX, worldY, worldZ);
                    BlockState state = blocks.getBlockState(cursor);
                    if (!state.isAir()) {
                        topY = worldY;
                        topState = state;
                        break;
                    }
                }

                if (topY == Integer.MIN_VALUE || topState == null) {
                    continue;
                }

                int bottomY = topY;
                for (int worldY = candidateBounds.min().y(); worldY < topY; worldY++) {
                    cursor.set(worldX, worldY, worldZ);
                    if (!blocks.getBlockState(cursor).isAir()) {
                        bottomY = worldY;
                        break;
                    }
                }

                PreviewColumn column = new PreviewColumn(worldX, worldZ, bottomY, topY, topState);
                sampled[index(width, worldX - candidateBounds.min().x(), worldZ - candidateBounds.min().z())] = column;
                hasBlocks = true;

                minX = Math.min(minX, worldX);
                minY = Math.min(minY, bottomY);
                minZ = Math.min(minZ, worldZ);
                maxX = Math.max(maxX, worldX);
                maxY = Math.max(maxY, topY);
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
                        minY,
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
        int bottomY,
        int topY,
        BlockState topState
) {
}
