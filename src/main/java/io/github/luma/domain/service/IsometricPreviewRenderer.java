package io.github.luma.domain.service;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.service.PreviewService.PreviewRenderData;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

final class IsometricPreviewRenderer {

    private static final int MAX_WIDTH = 320;
    private static final int MAX_HEIGHT = 196;
    private static final int MARGIN = 8;

    PreviewRenderData render(PreviewScene scene, BlockGetter blocks) {
        Bounds3i bounds = scene.frameBounds();
        int halfTileWidth = this.resolveHalfTileWidth(bounds);
        int halfTileHeight = Math.max(1, (int) Math.round(halfTileWidth / 2.0D));
        int blockHeight = Math.max(1, halfTileWidth);

        int canvasWidth = ((bounds.sizeX() + bounds.sizeZ() + 4) * halfTileWidth) + (MARGIN * 2);
        int canvasHeight = ((bounds.sizeX() + bounds.sizeZ() + 4) * halfTileHeight)
                + ((bounds.sizeY() + 4) * blockHeight)
                + (MARGIN * 2);
        BufferedImage image = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int originX = MARGIN + ((bounds.sizeZ() + 2) * halfTileWidth);
        int originY = MARGIN + ((bounds.sizeY() + 2) * blockHeight);

        DrawBounds drawBounds = new DrawBounds(canvasWidth, canvasHeight);
        if (!scene.hasBlocks()) {
            this.drawFootprint(graphics, bounds, originX, originY, halfTileWidth, halfTileHeight, drawBounds);
            graphics.dispose();
            return crop(image, drawBounds);
        }

        List<PreviewColumn> columns = scene.presentColumns().stream()
                .sorted(Comparator.comparingInt((PreviewColumn column) -> column.worldX() + column.worldZ() + column.topY())
                        .thenComparingInt(PreviewColumn::worldX)
                        .thenComparingInt(PreviewColumn::worldZ))
                .toList();
        for (PreviewColumn column : columns) {
            this.drawColumn(graphics, scene, blocks, bounds, originX, originY, halfTileWidth, halfTileHeight, blockHeight, drawBounds, column);
        }

        graphics.dispose();
        return crop(image, drawBounds);
    }

    static int shadeColor(int rgb, double factor) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        red = clampColor((int) Math.round(red * factor));
        green = clampColor((int) Math.round(green * factor));
        blue = clampColor((int) Math.round(blue * factor));
        return (red << 16) | (green << 8) | blue;
    }

    private void drawFootprint(
            Graphics2D graphics,
            Bounds3i bounds,
            int originX,
            int originY,
            int halfTileWidth,
            int halfTileHeight,
            DrawBounds drawBounds
    ) {
        int[] topXs = {
                projectX(originX, halfTileWidth, 0, 0),
                projectX(originX, halfTileWidth, bounds.sizeX(), 0),
                projectX(originX, halfTileWidth, bounds.sizeX(), bounds.sizeZ()),
                projectX(originX, halfTileWidth, 0, bounds.sizeZ())
        };
        int[] topYs = {
                projectY(originY, halfTileHeight, 0, 0),
                projectY(originY, halfTileHeight, bounds.sizeX(), 0),
                projectY(originY, halfTileHeight, bounds.sizeX(), bounds.sizeZ()),
                projectY(originY, halfTileHeight, 0, bounds.sizeZ())
        };
        this.paintFace(graphics, topXs, topYs, 0x5A5A5A, 0x3D3D3D);
        drawBounds.include(topXs, topYs);
    }

    private void drawColumn(
            Graphics2D graphics,
            PreviewScene scene,
            BlockGetter blocks,
            Bounds3i bounds,
            int originX,
            int originY,
            int halfTileWidth,
            int halfTileHeight,
            int blockHeight,
            DrawBounds drawBounds,
            PreviewColumn column
    ) {
        int relativeX = column.worldX() - bounds.min().x();
        int relativeZ = column.worldZ() - bounds.min().z();
        int relativeTopY = column.topY() - bounds.min().y();
        int sceneFloorY = bounds.min().y();

        int centerX = originX + ((relativeX - relativeZ) * halfTileWidth);
        int topY = originY + ((relativeX + relativeZ) * halfTileHeight) - (relativeTopY * blockHeight);

        int baseColor = this.baseColor(blocks, column);
        int topColor = shadeColor(baseColor, 1.08D);
        int southColor = shadeColor(baseColor, 0.82D);
        int eastColor = shadeColor(baseColor, 0.66D);

        int[] topXs = {centerX, centerX + halfTileWidth, centerX, centerX - halfTileWidth};
        int[] topYs = {topY, topY + halfTileHeight, topY + (halfTileHeight * 2), topY + halfTileHeight};
        this.paintFace(graphics, topXs, topYs, topColor, shadeColor(topColor, 0.62D));
        drawBounds.include(topXs, topYs);

        PreviewColumn southNeighbor = scene.columnAt(relativeX, relativeZ + 1);
        int southBaseY = southNeighbor == null
                ? sceneFloorY
                : Math.max(sceneFloorY, southNeighbor.topY() + 1);
        int southHeight = column.topY() - southBaseY + 1;
        if (southHeight > 0) {
            int sideHeight = southHeight * blockHeight;
            int[] southXs = {centerX - halfTileWidth, centerX, centerX, centerX - halfTileWidth};
            int[] southYs = {
                    topY + halfTileHeight,
                    topY + (halfTileHeight * 2),
                    topY + (halfTileHeight * 2) + sideHeight,
                    topY + halfTileHeight + sideHeight
            };
            this.paintFace(graphics, southXs, southYs, southColor, shadeColor(southColor, 0.58D));
            drawBounds.include(southXs, southYs);
        }

        PreviewColumn eastNeighbor = scene.columnAt(relativeX + 1, relativeZ);
        int eastBaseY = eastNeighbor == null
                ? sceneFloorY
                : Math.max(sceneFloorY, eastNeighbor.topY() + 1);
        int eastHeight = column.topY() - eastBaseY + 1;
        if (eastHeight > 0) {
            int sideHeight = eastHeight * blockHeight;
            int[] eastXs = {centerX + halfTileWidth, centerX, centerX, centerX + halfTileWidth};
            int[] eastYs = {
                    topY + halfTileHeight,
                    topY + (halfTileHeight * 2),
                    topY + (halfTileHeight * 2) + sideHeight,
                    topY + halfTileHeight + sideHeight
            };
            this.paintFace(graphics, eastXs, eastYs, eastColor, shadeColor(eastColor, 0.52D));
            drawBounds.include(eastXs, eastYs);
        }
    }

    private void paintFace(Graphics2D graphics, int[] xPoints, int[] yPoints, int fillRgb, int strokeRgb) {
        graphics.setColor(new Color(withAlpha(fillRgb, 0xFF), true));
        graphics.fillPolygon(xPoints, yPoints, xPoints.length);
        graphics.setColor(new Color(withAlpha(strokeRgb, 0xD8), true));
        graphics.drawPolygon(xPoints, yPoints, xPoints.length);
    }

    private int resolveHalfTileWidth(Bounds3i bounds) {
        double widthBudget = (MAX_WIDTH - (MARGIN * 2.0D))
                / Math.max(1.0D, bounds.sizeX() + bounds.sizeZ() + 2.0D);
        double heightBudget = (MAX_HEIGHT - (MARGIN * 2.0D))
                / Math.max(1.0D, ((bounds.sizeX() + bounds.sizeZ() + 2.0D) * 0.5D) + bounds.sizeY() + 2.0D);
        return Math.max(1, Math.min(12, (int) Math.floor(Math.min(widthBudget, heightBudget))));
    }

    private int baseColor(BlockGetter blocks, PreviewColumn column) {
        BlockState state = column.topState();
        if (state == null || state.isAir()) {
            return 0x5A5A5A;
        }

        MapColor mapColor = state.getMapColor(blocks, new BlockPos(column.worldX(), column.topY(), column.worldZ()));
        if (mapColor != null && mapColor != MapColor.NONE) {
            return mapColor.col;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int hash = Math.abs(blockId.hashCode());
        int red = 72 + (hash & 0x63);
        int green = 72 + ((hash >> 8) & 0x63);
        int blue = 72 + ((hash >> 16) & 0x63);
        return (red << 16) | (green << 8) | blue;
    }

    private static int projectX(int originX, int halfTileWidth, int x, int z) {
        return originX + ((x - z) * halfTileWidth);
    }

    private static int projectY(int originY, int halfTileHeight, int x, int z) {
        return originY + ((x + z) * halfTileHeight);
    }

    private static PreviewRenderData crop(BufferedImage image, DrawBounds drawBounds) {
        if (!drawBounds.hasContent()) {
            return new PreviewRenderData(1, 1, new int[]{0x00000000});
        }

        int minX = Math.max(0, drawBounds.minX - 2);
        int minY = Math.max(0, drawBounds.minY - 2);
        int maxX = Math.min(image.getWidth() - 1, drawBounds.maxX + 2);
        int maxY = Math.min(image.getHeight() - 1, drawBounds.maxY + 2);
        int width = Math.max(1, (maxX - minX) + 1);
        int height = Math.max(1, (maxY - minY) + 1);
        int[] pixels = image.getRGB(minX, minY, width, height, null, 0, width);
        return new PreviewRenderData(width, height, pixels);
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static final class DrawBounds {

        private int minX;
        private int minY;
        private int maxX;
        private int maxY;
        private boolean initialized;

        private DrawBounds(int canvasWidth, int canvasHeight) {
            this.minX = canvasWidth;
            this.minY = canvasHeight;
            this.maxX = -1;
            this.maxY = -1;
            this.initialized = false;
        }

        private void include(int[] xPoints, int[] yPoints) {
            for (int index = 0; index < xPoints.length; index++) {
                this.minX = Math.min(this.minX, xPoints[index]);
                this.minY = Math.min(this.minY, yPoints[index]);
                this.maxX = Math.max(this.maxX, xPoints[index]);
                this.maxY = Math.max(this.maxY, yPoints[index]);
            }
            this.initialized = true;
        }

        private boolean hasContent() {
            return this.initialized;
        }
    }
}
