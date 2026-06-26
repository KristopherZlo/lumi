package io.github.luma.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SpecialThanksLanguageTest {

    @Test
    void specialThanksDescriptionDoesNotMentionSkins() throws IOException {
        for (Path file : Files.list(Path.of("src/main/resources/assets/lumi/lang")).toList()) {
            String source = Files.readString(file);

            assertFalse(source.toLowerCase().contains("skins"), file + " should not mention skins");
            assertFalse(source.contains("Скины"), file + " should not mention skins");
        }
    }
}
