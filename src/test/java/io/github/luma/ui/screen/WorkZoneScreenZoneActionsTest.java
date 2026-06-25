package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneScreenZoneActionsTest {

    private final String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/WorkZoneScreen.java"));

    WorkZoneScreenZoneActionsTest() throws IOException {
    }

    @Test
    void saveZoneButtonOpensZoneSaveDialog() {
        String methodBody = methodBody(
                "    private FlowLayout saveZoneSection(WorkZone zone, boolean active) {",
                "    private FlowLayout zoneHistorySection(WorkZone zone) {"
        );

        assertTrue(methodBody.contains("openZoneSaveDialog(zone.id())"));
        assertFalse(methodBody.contains("this.saveZone(zone.id())"));
    }

    @Test
    void zoneRestoreRequiresConfirmationBeforeStartingRestore() {
        String methodBody = methodBody(
                "        public void requestRestore(ProjectVariant variant, ProjectVersion version) {",
                "        public void openBranchDialog(ProjectVersion version) {"
        );

        assertTrue(methodBody.contains("pendingRestoreVersionId = version.id()"));
        assertTrue(methodBody.contains("refresh(\"luma.status.restore_confirmation_required\")"));
        assertFalse(methodBody.contains("partialRestore("));
    }

    @Test
    void zoneBranchActionOpensBranchCreationDialog() {
        String methodBody = methodBody(
                "        public void openBranchDialog(ProjectVersion version) {",
                "        public void toggleTagEditor(ProjectVersion version) {"
        );

        assertTrue(methodBody.contains("pendingBranchBaseVersionId = version == null ? \"\" : version.id()"));
        assertFalse(methodBody.contains("router.openVariants("));
    }

    @Test
    void workZoneScreenOwnsBranchAndRestoreDialogViews() {
        assertTrue(this.source.contains("new BranchCreationDialogView(this.projectController, new BranchDialogActions())"));
        assertTrue(this.source.contains("new RestoreConfirmationDialogView(new RestoreDialogActions())"));
    }

    @Test
    void altSaveOnWorkZoneScreenOpensZoneSaveDialog() throws IOException {
        String clientSource = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(clientSource.contains("client.screen instanceof WorkZoneScreen"));
        assertTrue(clientSource.contains("workZoneScreen.openZoneSaveDialog()"));
    }

    private String methodBody(String start, String end) {
        int methodIndex = this.source.indexOf(start);
        int nextMethodIndex = this.source.indexOf(end, methodIndex);

        assertTrue(methodIndex >= 0, "WorkZoneScreen should keep " + start.trim());
        assertTrue(nextMethodIndex > methodIndex, "The method should be bounded by " + end.trim());

        return this.source.substring(methodIndex, nextMethodIndex);
    }
}
