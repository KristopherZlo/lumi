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
    void focusedZoneUsesCurrentBuildSectionInsteadOfSaveZoneSection() {
        String methodBody = methodBody(
                "    private FlowLayout zoneCurrentBuildSection(WorkZone zone, boolean active) {",
                "    private FlowLayout zoneHistorySection(WorkZone zone) {"
        );

        assertTrue(this.source.contains("body.child(this.zoneCurrentBuildSection(focused, active));"));
        assertFalse(this.source.contains("body.child(this.saveZoneSection(focused, active));"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.build.status_title\")"));
        assertTrue(methodBody.contains("\"luma.build.current_idea\""));
        assertTrue(methodBody.contains("\"luma.build.current_place\""));
        assertTrue(methodBody.contains("this.state.pendingChanges()"));
        assertTrue(methodBody.contains("LumaUi.statChip(Component.translatable(\"luma.build.blocks_placed\")"));
        assertTrue(methodBody.contains("LumaUi.statChip(Component.translatable(\"luma.build.blocks_removed\")"));
        assertTrue(methodBody.contains("LumaUi.statChip(Component.translatable(\"luma.build.blocks_changed\")"));
        assertTrue(methodBody.contains("openZoneSaveDialog(zone.id())"));
        assertTrue(methodBody.contains("openZoneAmendDialog(zone.id(), activeHead)"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.action.see_changes\")"));
        assertTrue(methodBody.contains("requestCompareOverlay("));
        assertTrue(methodBody.contains("CompareScreenController.CURRENT_WORLD_REFERENCE"));
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
    void zoneRestoreSwitchesToTheSelectedCommit() {
        String methodBody = methodBody(
                "    private void executeZoneRestore(ProjectVersion version, String zoneId, RestoreEntityTypeSelection selection) {",
                "    private void clearPendingRestore() {"
        );

        assertTrue(methodBody.contains("restoreVersion(this.effectiveProjectName(), version.id(), version.variantId(), selection)"));
        assertFalse(methodBody.contains("partialRestore("));
    }

    @Test
    void zoneHistoryHighlightsBranchHeadInsteadOfNewestCommit() {
        String methodBody = methodBody(
                "    private FlowLayout zoneHistorySection(WorkZone zone) {",
                "    private FlowLayout zoneHistoryToolbar() {"
        );

        assertTrue(methodBody.contains("this.zoneSaveCard(latest, ProjectUiSupport.isVariantHead(this.state.variants(), latest))"));
        assertTrue(methodBody.contains("this.zoneSaveCard(version, ProjectUiSupport.isVariantHead(this.state.variants(), version))"));
        assertFalse(methodBody.contains("this.zoneSaveCard(latest, true)"));
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
    void createZoneInputHintsZoneName() {
        String methodBody = methodBody(
                "    private FlowLayout createZoneSection() {",
                "    private FlowLayout zoneListSection() {"
        );

        assertTrue(methodBody.contains("input.setHint(Component.translatable(\"luma.zones.delete_input\"))"));
    }

    @Test
    void saveZoneDialogRepeatsSaveBuildFields() {
        String methodBody = methodBody(
                "    private FlowLayout zoneSaveDialogOverlay() {",
                "    private FlowLayout zoneHistorySection(WorkZone zone) {"
        );

        assertTrue(methodBody.contains("LumaUi.closeHeader(Component.translatable(\"luma.zones.save_title\")"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.save.summary_help\")"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.save.name_input\")"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.save.tags_title\")"));
        assertTrue(methodBody.contains("new TagSuggestionComponent("));
        assertTrue(this.source.contains("ProjectVersionTags.parse(this.saveTags)"));
        assertTrue(methodBody.contains("Component.translatable(\"luma.action.amend_version\")"));
        assertTrue(methodBody.contains("startZoneDialogSave(true)"));
    }

    @Test
    void zoneDeleteDialogHintsZoneNameAndUsesDangerButton() {
        String methodBody = methodBody(
                "    private FlowLayout zoneDeleteDialogOverlay() {",
                "    private FlowLayout zoneHistorySection(WorkZone zone) {"
        );

        assertTrue(methodBody.contains("input.setHint(Component.literal(zone.name()))"));
        assertTrue(methodBody.contains("LumaUi.dangerButton"));
    }

    @Test
    void altSaveOnWorkZoneScreenOpensZoneSaveDialog() throws IOException {
        String clientSource = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(clientSource.contains("client.screen instanceof WorkZoneScreen"));
        assertTrue(clientSource.contains("workZoneScreen.openZoneSaveDialog()"));
    }

    @Test
    void quickSaveScreenUsesZoneTitleWhenAnActiveZoneExists() throws IOException {
        String quickSaveSource = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/QuickSaveScreen.java"));

        assertTrue(quickSaveSource.contains("this.activeZoneName"));
        assertTrue(quickSaveSource.contains("\"luma.zones.save_title\""));
        assertTrue(quickSaveSource.contains("\"luma.screen.save.title\""));
    }

    @Test
    void workZoneScreenPollsLocalStateOnTicks() {
        String methodBody = methodBody(
                "    protected void onLumaTick() {",
                "    private void requestCompareOverlay(String leftReference, String rightReference) {"
        );

        assertTrue(methodBody.contains("this.controller.load(this.projectName, this.status)"));
        assertTrue(methodBody.contains("!refreshed.equals(this.state)"));
    }

    @Test
    void renderModeButtonLabelsCurrentMode() {
        String methodBody = methodBody(
                "    private String renderModeToggleLabelKey() {",
                "    private FlowLayout zoneCard(WorkZone zone, boolean active) {"
        );

        assertTrue(methodBody.contains("case FOCUSED -> \"luma.zones.render_focused\";"));
        assertTrue(methodBody.contains("case ALL -> \"luma.zones.render_all\";"));
        assertTrue(methodBody.contains("case HIDDEN -> \"luma.zones.render_hidden\";"));
    }

    private String methodBody(String start, String end) {
        int methodIndex = this.source.indexOf(start);
        int nextMethodIndex = this.source.indexOf(end, methodIndex);

        assertTrue(methodIndex >= 0, "WorkZoneScreen should keep " + start.trim());
        assertTrue(nextMethodIndex > methodIndex, "The method should be bounded by " + end.trim());

        return this.source.substring(methodIndex, nextMethodIndex);
    }
}
