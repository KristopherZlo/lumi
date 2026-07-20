package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        String tags = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiVersionTagsScreen.java"));
        String rename = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiVersionRenameScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(details.contains("previews.texture(dimensionId, version.id())"));
        assertTrue(details.contains("luma.save_details.raw_info_id"));
        assertTrue(details.contains("luma.save_details.raw_info_author"));
        assertTrue(details.contains("luma.save_details.raw_info_type"));
        int restoreAction = details.indexOf("\"rollback\"");
        int branchAction = details.indexOf("\"branch\"", restoreAction);
        int compareAction = details.indexOf("\"see-changes\"", branchAction);
        int deleteAction = details.indexOf("\"trash\"", compareAction);
        assertTrue(restoreAction < branchAction);
        assertTrue(branchAction < compareAction);
        assertTrue(compareAction < deleteAction);
        assertTrue(details.contains("compare.active = compareToParent.isPresent()"));
        assertTrue(details.contains("luma.action.delete_save"));
        assertTrue(details.contains("luma.action.edit_tags"));
        assertTrue(details.contains("new LumiVersionTagsScreen("));
        assertTrue(details.contains("updateTags.accept(replacement)"));
        assertTrue(tags.contains("luma.action.save_tags"));
        assertTrue(tags.contains("luma.action.cancel"));
        assertTrue(tags.contains("luma.history.tags_input"));
        assertTrue(tags.contains("VersionTags.parse(tags.getValue())"));
        assertTrue(details.contains("luma.action.rename_save"));
        assertTrue(details.contains("\"edit-text\""));
        assertTrue(details.contains(
                "navigationControlX(panelX, panelWidth) - 8 - 26"));
        assertTrue(details.contains("new LumiVersionRenameScreen("));
        assertTrue(details.contains("rename.accept(replacement)"));
        assertTrue(details.contains("luma.save_details.create_idea"));
        assertFalse(details.contains("luma.action.amend_version"));
        assertFalse(details.contains("luma.action.restore_selected_area"));
        assertFalse(details.contains("nameEditor"));
        assertFalse(details.contains("editingName"));
        assertTrue(rename.contains("luma.action.save"));
        assertTrue(rename.contains("luma.action.cancel"));
        assertFalse(rename.contains("addIconButton"));
        assertTrue(rename.contains("new VersionDisplayName(name.getValue())"));
        assertTrue(details.contains("luma.action.zoom_out"));
        assertTrue(details.contains("luma.action.zoom_in"));
        assertTrue(details.contains("luma.action.preview_pan_up"));
        assertTrue(details.contains("luma.action.preview_pan_down"));
        assertTrue(details.contains("restoreButton.active = !readOnly"));
        assertTrue(details.contains(
                "remove.active = !readOnly && !VersionText.immutable(version)"));
        assertTrue(details.contains("sourceWidth, sourceHeight"));
        assertTrue(dashboard.contains("\"folder\", \"luma.action.open_details\""));
        assertTrue(client.contains("new LumiVersionDetailsScreen("));
        assertTrue(client.contains("() -> openRestore(parent, version)"));
        assertTrue(client.contains("() -> openDelete(parent, version)"));
        assertTrue(client.contains("NETWORKING.updateVersionTags(version.id(), tags)"));
        assertTrue(client.contains("NETWORKING.renameVersion(version.id(), name)"));
        assertTrue(client.contains("NETWORKING.createBranchAt(name, version.id())"));
        assertFalse(client.contains("var partialRestore ="));
    }
}
