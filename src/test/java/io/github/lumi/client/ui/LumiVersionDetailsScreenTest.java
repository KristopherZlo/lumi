package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiVersionDetailsScreenTest {
    @Test
    void opensOneSaveWithPreviewMetadataAndExistingActions() throws Exception {
        String details = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiVersionDetailsScreen.java"));
        String dashboard = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(details.contains("previews.texture(dimensionId, version.id())"));
        assertTrue(details.contains("luma.save_details.raw_info_id"));
        assertTrue(details.contains("luma.save_details.raw_info_author"));
        assertTrue(details.contains("luma.save_details.raw_info_type"));
        assertTrue(details.contains("luma.action.restore"));
        assertTrue(details.contains("compare.active = compareToParent.isPresent()"));
        assertTrue(details.contains("luma.action.delete_save"));
        assertTrue(details.contains("luma.action.edit_tags"));
        assertTrue(details.contains("luma.action.save_tags"));
        assertTrue(details.contains("updateTags.accept(tags)"));
        assertTrue(dashboard.contains("\"eye-open\", \"luma.action.open_details\""));
        assertTrue(client.contains("new LumiVersionDetailsScreen("));
        assertTrue(client.contains("() -> openRestore(parent, version)"));
        assertTrue(client.contains("() -> openDelete(parent, version)"));
        assertTrue(client.contains("NETWORKING.updateVersionTags(version.id(), tags)"));
    }
}
