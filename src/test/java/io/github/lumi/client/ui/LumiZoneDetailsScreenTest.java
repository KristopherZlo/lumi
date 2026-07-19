package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneDetailsScreenTest {
    @Test
    void exposesLegacyZoneAmendAndSeeChangesActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiZoneDetailsScreen.java"));

        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("\"see-changes\""));
        assertTrue(source.contains("showChanges.run()"));
    }
}
