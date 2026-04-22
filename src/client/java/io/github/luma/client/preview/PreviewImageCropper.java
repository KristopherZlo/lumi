package io.github.luma.client.preview;

import com.mojang.blaze3d.platform.NativeImage;

final class PreviewImageCropper {

    private static final int PADDING = 2;

    CapturedPreviewImage crop(NativeImage source) {
        int[] pixels = source.getPixelsABGR();
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if (alpha(pixels[index(source.getWidth(), x, y)]) == 0) {
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        if (maxX < 0 || maxY < 0) {
            source.close();
            return new CapturedPreviewImage(new NativeImage(1, 1, false), 1, 1);
        }

        int cropMinX = Math.max(0, minX - PADDING);
        int cropMinY = Math.max(0, minY - PADDING);
        int cropMaxX = Math.min(source.getWidth() - 1, maxX + PADDING);
        int cropMaxY = Math.min(source.getHeight() - 1, maxY + PADDING);
        int width = Math.max(1, (cropMaxX - cropMinX) + 1);
        int height = Math.max(1, (cropMaxY - cropMinY) + 1);

        NativeImage cropped = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cropped.setPixelABGR(x, y, pixels[index(source.getWidth(), cropMinX + x, cropMinY + y)]);
            }
        }

        source.close();
        return new CapturedPreviewImage(cropped, width, height);
    }

    private static int alpha(int abgr) {
        return (abgr >>> 24) & 0xFF;
    }

    private static int index(int width, int x, int y) {
        return x + (y * width);
    }

    record CapturedPreviewImage(NativeImage image, int width, int height) implements AutoCloseable {

        @Override
        public void close() {
            this.image.close();
        }
    }
}
