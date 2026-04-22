package io.github.luma.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewImageCropperTest {

    private final PreviewImageCropper cropper = new PreviewImageCropper();

    @Test
    void cropsTransparentMarginsAroundRenderedPixels() {
        NativeImage image = new NativeImage(12, 10, false);
        image.setPixelABGR(5, 4, 0xFF3366CC);

        try (PreviewImageCropper.CapturedPreviewImage cropped = this.cropper.crop(image)) {
            assertEquals(5, cropped.width());
            assertEquals(5, cropped.height());
            assertEquals(0xFF3366CC, cropped.image().getPixelsABGR()[12]);
        }
    }

    @Test
    void returnsSingleTransparentPixelForEmptyCapture() {
        NativeImage image = new NativeImage(8, 8, false);

        try (PreviewImageCropper.CapturedPreviewImage cropped = this.cropper.crop(image)) {
            assertEquals(1, cropped.width());
            assertEquals(1, cropped.height());
            assertEquals(0x00000000, cropped.image().getPixelsABGR()[0]);
        }
    }
}
