package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksClientCacheTest {

    @Test
    void clientInitializerPreloadsSpecialThanksSkinsAtStartup() throws IOException {
        String client = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));
        String cache = Files.readString(Path.of("src/client/java/io/github/luma/client/specialthanks/SpecialThanksClientCache.java"));

        assertTrue(client.contains("SpecialThanksClientCache.getInstance().preload(Minecraft.getInstance())"));
        assertTrue(cache.contains("preloadSkins()"));
        assertTrue(cache.contains("textureFor(entry.skinName())"));
    }
}
