package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

class PreviewImageCropperTest {
    private final PreviewImageCropper cropper = new PreviewImageCropper();

    @Test
    void cropsTransparentMarginsAroundRenderedPixels() throws ReflectiveOperationException {
        NativeImage image = new NativeImage(12, 10, false);
        image.setPixelABGR(5, 4, 0xFF3366CC);

        try (NativeImage cropped = cropper.crop(image)) {
            assertEquals(5, cropped.getWidth());
            assertEquals(5, cropped.getHeight());
            assertEquals(0xFF3366CC, cropped.getPixelsABGR()[12]);
        }
        assertReleased(image);
    }

    @Test
    void returnsSingleTransparentPixelForEmptyCapture() throws ReflectiveOperationException {
        NativeImage image = new NativeImage(8, 8, false);

        try (NativeImage cropped = cropper.crop(image)) {
            assertEquals(1, cropped.getWidth());
            assertEquals(1, cropped.getHeight());
            assertEquals(0, cropped.getPixelsABGR()[0]);
        }
        assertReleased(image);
    }

    @Test
    void closesTheSourceWhenPixelReadFails() throws ReflectiveOperationException {
        NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, 1, 1, false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> cropper.crop(image));
        assertReleased(image);
    }

    private static void assertReleased(NativeImage image) throws ReflectiveOperationException {
        var pointer = NativeImage.class.getDeclaredField("pixels");
        pointer.setAccessible(true);
        assertEquals(0L, pointer.getLong(image));
    }
}
