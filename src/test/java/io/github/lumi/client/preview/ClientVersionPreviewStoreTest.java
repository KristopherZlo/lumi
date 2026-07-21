package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientVersionPreviewStoreTest {
    @Test
    void keepsEncodingOffTheRenderThreadAndTexturesBounded() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/ClientVersionPreviewStore.java"));

        assertTrue(source.contains("newSingleThreadExecutor"));
        assertTrue(source.contains("MAX_TEXTURES = 32"));
        assertTrue(source.contains("while (textures.size() > MAX_TEXTURES"));
        assertTrue(source.contains("getTextureManager().release"));
        assertTrue(source.contains("VersionPreviewRepository"));
    }

    @Test
    void treatsFullyTransparentImagesAsMissing() {
        try (NativeImage transparent = new NativeImage(2, 2, false);
             NativeImage visible = new NativeImage(2, 2, false)) {
            visible.setPixelABGR(1, 1, 0x01000000);

            assertFalse(ClientVersionPreviewStore.hasVisiblePixel(transparent));
            assertTrue(ClientVersionPreviewStore.hasVisiblePixel(visible));
        }
    }
}
