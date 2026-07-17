package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
