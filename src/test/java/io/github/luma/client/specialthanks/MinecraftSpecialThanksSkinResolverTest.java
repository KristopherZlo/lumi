package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSpecialThanksSkinResolverTest {

    @Test
    void directSkinUrlsUseVanillaSkinProcessing() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/MinecraftSpecialThanksSkinResolver.java"
        ));

        assertTrue(source.contains("client.getTextureManager(),\n                client"));
        assertTrue(source.contains("downloadAndRegisterSkin(textureId, cachePath, skinUrl, true)"));
    }
}
