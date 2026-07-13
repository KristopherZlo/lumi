package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksClientCacheTest {

    @Test
    void specialThanksScreenPreparesSkinsAfterItOpens() throws IOException {
        String client = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));
        String cache = Files.readString(Path.of("src/client/java/io/github/luma/client/specialthanks/SpecialThanksClientCache.java"));
        String screen = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SpecialThanksScreen.java"));

        assertFalse(client.contains("SpecialThanksClientCache.getInstance()"));
        assertTrue(screen.contains("this.specialThanks.prepare(this.client)"));
        assertTrue(cache.contains("preloadSkins()"));
        assertTrue(cache.contains("skinFor(entry)"));
    }
}
