package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksScreenTest {

    @Test
    void moreScreenOpensDedicatedSpecialThanksPage() throws IOException {
        String moreScreen = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/MoreScreen.java"),
                StandardCharsets.UTF_8
        );
        String router = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ScreenRouter.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(moreScreen.contains("specialThanksSection()"));
        assertTrue(router.contains("openSpecialThanks"));
    }

    @Test
    void specialThanksPageLoadsCatalogAndMinecraftSkinHeads() throws IOException {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/SpecialThanksScreen.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("SpecialThanksCatalogSource"));
        assertTrue(source.contains("MinecraftSpecialThanksSkinResolver"));
        assertTrue(source.contains("rebuildPreservingScroll"));
        assertTrue(source.contains("UIComponents.texture"));
        assertTrue(source.contains("entry.skinName()"));
        assertTrue(source.contains("entry.description()"));
    }
}
