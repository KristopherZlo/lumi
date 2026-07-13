package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSpecialThanksSkinResolverTest {

    @Test
    void directSkinUrlsUseVanillaProcessingAndBundledFallback() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/MinecraftSpecialThanksSkinResolver.java"
        )).replace("\r\n", "\n");

        assertTrue(source.contains("client.getTextureManager(),\n                client"));
        assertTrue(source.contains("downloadAndRegisterSkin(textureId, cachePath, skinUrl, true)"));
        assertTrue(source.contains("Map<SpecialThanksEntry, CompletableFuture<PlayerSkin>>"));
        assertTrue(source.contains("new ClientAsset.ResourceTexture(texture, texture)"));
        assertTrue(source.contains("Special Thanks skins require HTTPS"));
        assertTrue(source.contains("using bundled texture"));
    }
}
