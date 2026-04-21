package io.github.luma.domain.service;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class PreviewService {

    private static final int PREVIEW_LIMIT = 96;

    public PreviewInfo capture(ProjectLayout layout, String versionId, Bounds3i bounds, ServerLevel level) throws IOException {
        return this.write(layout, versionId, this.sample(bounds, level));
    }

    public PreviewRenderData sample(Bounds3i bounds, ServerLevel level) {
        int width = Math.max(1, Math.min(PREVIEW_LIMIT, bounds.sizeX()));
        int depth = Math.max(1, Math.min(PREVIEW_LIMIT, bounds.sizeZ()));
        int[] pixels = new int[width * depth];

        double stepX = (double) bounds.sizeX() / (double) width;
        double stepZ = (double) bounds.sizeZ() / (double) depth;

        for (int imageX = 0; imageX < width; imageX++) {
            for (int imageZ = 0; imageZ < depth; imageZ++) {
                int worldX = bounds.min().x() + Math.min(bounds.sizeX() - 1, (int) Math.floor(imageX * stepX));
                int worldZ = bounds.min().z() + Math.min(bounds.sizeZ() - 1, (int) Math.floor(imageZ * stepZ));
                pixels[(imageZ * width) + imageX] = this.resolveTopColor(level, bounds, worldX, worldZ);
            }
        }

        return new PreviewRenderData(width, depth, pixels);
    }

    public PreviewInfo write(ProjectLayout layout, String versionId, PreviewRenderData renderData) throws IOException {
        Files.createDirectories(layout.previewsDir());

        BufferedImage image = new BufferedImage(renderData.width(), renderData.depth(), BufferedImage.TYPE_INT_ARGB);
        for (int imageX = 0; imageX < renderData.width(); imageX++) {
            for (int imageZ = 0; imageZ < renderData.depth(); imageZ++) {
                image.setRGB(imageX, imageZ, renderData.pixels()[(imageZ * renderData.width()) + imageX]);
            }
        }

        ImageIO.write(image, "png", layout.previewFile(versionId).toFile());
        return new PreviewInfo(layout.previewFile(versionId).getFileName().toString(), renderData.width(), renderData.depth());
    }

    private int resolveTopColor(ServerLevel level, Bounds3i bounds, int worldX, int worldZ) {
        for (int worldY = bounds.max().y(); worldY >= bounds.min().y(); worldY--) {
            BlockState state = level.getBlockState(new BlockPos(worldX, worldY, worldZ));
            if (!state.isAir()) {
                return this.colorForState(state);
            }
        }

        return 0x44000000;
    }

    private int colorForState(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int hash = Math.abs(blockId.hashCode());
        int red = 80 + (hash & 0x5F);
        int green = 80 + ((hash >> 8) & 0x5F);
        int blue = 80 + ((hash >> 16) & 0x5F);

        if (state.is(Blocks.WATER)) {
            red = 60;
            green = 110;
            blue = 220;
        } else if (state.is(Blocks.GRASS_BLOCK)) {
            red = 90;
            green = 170;
            blue = 90;
        }

        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public record PreviewRenderData(int width, int depth, int[] pixels) {
    }
}
