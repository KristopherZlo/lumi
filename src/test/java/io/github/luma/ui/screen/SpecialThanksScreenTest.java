package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void specialThanksPageRendersAnimatedPlayerShowcase() throws IOException {
        String source = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/SpecialThanksScreen.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("SpecialThanksClientCache"));
        assertTrue(source.contains("SpecialThanksPlayerShowcaseComponent"));
        assertTrue(source.contains("addListener"));
        assertTrue(source.contains("removeListener"));
        assertTrue(source.contains("rebuildPreservingScroll"));
        assertFalse(source.contains("UIComponents.texture"));
        assertTrue(source.contains("new SpecialThanksPlayerShowcaseComponent(entry)"));
        assertTrue(source.contains("entry.description()"));
    }
}
