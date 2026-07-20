package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(source.contains("luma.action.refresh_preview"));
        assertTrue(source.contains("submission.requestId().ifPresent(previewCapture)"));
        assertTrue(source.contains("accepted.run()"));
        assertTrue(source.contains("luma.history.tags_input"));
        assertTrue(source.contains("tags.getValue()"));
        assertTrue(source.contains("luma.zones.save_button"));
        assertTrue(source.contains("ZONE(\"luma.zones.save_title\""));
    }
}
