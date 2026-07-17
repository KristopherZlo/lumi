package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSaveScreenTest {
    @Test
    void amendEntryPrefillsTheLatestMessageAndMakesAmendPrimary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiSaveScreen.java"));

        assertTrue(source.contains("message.setValue(initialMessage)"));
        assertTrue(source.contains("preferredIntent == SaveScreenController.Intent.AMEND"));
        assertTrue(source.contains("submit(preferredIntent)"));
        assertTrue(source.contains("addLegacyIconButton"));
    }
}
