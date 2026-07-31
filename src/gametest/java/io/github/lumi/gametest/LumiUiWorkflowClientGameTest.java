package io.github.lumi.gametest;

import io.github.lumi.client.ui.LumiAmendConfirmationScreen;
import io.github.lumi.client.ui.LumiBranchScreen;
import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiComparePickerScreen;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiDeleteBranchScreen;
import io.github.lumi.client.ui.LumiDeleteVersionScreen;
import io.github.lumi.client.ui.LumiDeleteZoneScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.LumiVersionDetailsScreen;
import io.github.lumi.client.ui.LumiVersionTagsScreen;
import io.github.lumi.client.ui.LumiZoneDetailsScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/** In-game contract for dirty, saved, multi-version, and multi-branch controls. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiUiWorkflowClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.UI)
                || LumiHistoryBenchmarkConfig.enabled()) return;
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.run(
                    context, "ui-workflow", (test, world, report) -> {
                LumiUiTestDriver ui = new LumiUiTestDriver(test);
                LumiBehaviorActions actions = new LumiBehaviorActions(
                        world.getServer(), report);
                LumiBehaviorOperations operations = new LumiBehaviorOperations(
                        test, world.getServer(), report);
                ui.completeOnboardingIfShown();
                ui.awaitHistory();

                BlockPos first = actions.surfacePosition(2, 2);
                actions.placeBlocks("ui_dirty_first", Items.STONE, List.of(first));
                verifyDirtySaveForm(test, ui, report);
                CommitId firstSave = operations.save("workflow-first");

                BlockPos second = actions.surfacePosition(3, 2);
                actions.placeBlocks("ui_dirty_second", Items.DEEPSLATE, List.of(second));
                verifyAmendForm(test, ui, report);
                CommitId secondSave = operations.save("workflow-second");

                verifyVersionDetails(test, ui, secondSave, report);
                verifyBranches(test, ui, operations, report);
                verifyCompare(ui, report);
                verifyZones(test, ui, report);

                report.event("ui_contract", "stateful_workflow", "succeeded",
                        0, 0, "SAVE HIST DETAIL RESTORE BRANCH COMPARE ZONE; first="
                                + firstSave.hex() + ";second=" + secondSave.hex());
            });
        }
    }

    private static void verifyDirtySaveForm(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            LumiBehaviorReport report) {
        ui.openDashboard();
        ui.assertButtonEventually(
                LumiDashboardScreen.class, "luma.action.save_build", true);
        ui.assertButtonEventually(
                LumiDashboardScreen.class, "luma.action.amend_version", true);
        ui.pressUniqueButton(LumiDashboardScreen.class, "luma.action.save_build");
        context.waitForScreen(LumiSaveScreen.class);
        ui.assertFocusedText(LumiSaveScreen.class, "");
        ui.assertButton(LumiSaveScreen.class, "luma.action.save_build", false);
        ui.assertButton(LumiSaveScreen.class, "luma.action.amend_version", false);
        ui.typeIntoFocusedTextBox(LumiSaveScreen.class, "form-ready");
        ui.assertButtonEventually(
                LumiSaveScreen.class, "luma.action.save_build", true);
        ui.assertButtonEventually(
                LumiSaveScreen.class, "luma.action.amend_version", true);
        report.event("ui_contract", "dirty_save_form", "succeeded", 0, 0,
                "HIST-01..02 SAVE-01 SAVE-03..04 SAVE-06");
        ui.closeScreen(LumiSaveScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyAmendForm(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            LumiBehaviorReport report) {
        ui.openDashboard();
        ui.assertButtonEventually(
                LumiDashboardScreen.class, "luma.action.amend_version", true);
        ui.pressUniqueButton(
                LumiDashboardScreen.class, "luma.action.amend_version");
        context.waitForScreen(LumiSaveScreen.class);
        ui.assertFocusedText(LumiSaveScreen.class, "workflow-first");
        ui.assertButtonEventually(
                LumiSaveScreen.class, "luma.action.amend_version", true);
        ui.pressUniqueButton(
                LumiSaveScreen.class, "luma.action.amend_version");
        context.waitForScreen(LumiAmendConfirmationScreen.class);
        ui.assertButton(LumiAmendConfirmationScreen.class, "gui.yes", true);
        ui.assertButton(LumiAmendConfirmationScreen.class, "gui.no", true);
        ui.pressUniqueButton(LumiAmendConfirmationScreen.class, "gui.no");
        context.waitForScreen(LumiSaveScreen.class);
        ui.assertFocusedText(LumiSaveScreen.class, "workflow-first");
        report.event("ui_contract", "amend_prefill", "succeeded", 0, 0,
                "HIST-02 SAVE-04 SAVE-10");
        ui.closeScreen(LumiSaveScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyVersionDetails(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            CommitId save,
            LumiBehaviorReport report) {
        ui.openVersionDetails(save);
        for (String action : List.of(
                "luma.action.restore", "luma.save_details.create_idea",
                "luma.action.see_changes", "luma.action.delete_save",
                "luma.action.rename_save", "luma.action.edit_tags",
                "luma.action.zoom_in", "luma.action.zoom_out",
                "luma.action.preview_pan_up", "luma.action.preview_pan_down")) {
            ui.assertButton(LumiVersionDetailsScreen.class, action, true);
        }

        ui.pressUniqueButton(LumiVersionDetailsScreen.class, "luma.action.restore");
        context.waitForScreen(LumiRestoreScreen.class);
        ui.assertButton(LumiRestoreScreen.class, "luma.action.restore", true);
        ui.assertButton(LumiRestoreScreen.class, "luma.action.cancel", true);
        ui.assertButtonCount(LumiRestoreScreen.class,
                "luma.action.restore_whole_save", 0);
        ui.closeScreen(LumiRestoreScreen.class, LumiVersionDetailsScreen.class);

        ui.pressUniqueButton(
                LumiVersionDetailsScreen.class, "luma.save_details.create_idea");
        context.waitForScreen(LumiBranchScreen.class);
        ui.assertButton(LumiBranchScreen.class, "luma.action.variant_create", false);
        ui.typeIntoFocusedTextBox(LumiBranchScreen.class, "detail-idea");
        ui.assertButton(LumiBranchScreen.class, "luma.action.variant_create", true);
        ui.closeScreen(LumiBranchScreen.class, LumiVersionDetailsScreen.class);

        ui.pressUniqueButton(LumiVersionDetailsScreen.class, "luma.action.edit_tags");
        context.waitForScreen(LumiVersionTagsScreen.class);
        ui.assertButton(LumiVersionTagsScreen.class, "luma.action.save_tags", true);
        ui.assertButton(LumiVersionTagsScreen.class, "luma.action.cancel", true);
        ui.closeScreen(LumiVersionTagsScreen.class, LumiVersionDetailsScreen.class);

        ui.pressUniqueButton(LumiVersionDetailsScreen.class, "luma.action.delete_save");
        context.waitForScreen(LumiDeleteVersionScreen.class);
        ui.assertButton(LumiDeleteVersionScreen.class, "luma.action.delete_save", true);
        ui.assertButton(LumiDeleteVersionScreen.class, "luma.action.cancel", true);
        ui.closeScreen(LumiDeleteVersionScreen.class, LumiVersionDetailsScreen.class);
        report.event("ui_contract", "version_details", "succeeded", 0, 0,
                "HIST-10 VER-02..08 REST-01 REST-07");
        ui.closeScreen(LumiVersionDetailsScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyBranches(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            LumiBehaviorOperations operations,
            LumiBehaviorReport report) throws IOException {
        BranchName main = operations.activeBranch();
        BranchName idea = operations.createBranch("workflow-idea").name();
        if (!operations.activeBranch().equals(main)) {
            operations.switchBranch("workflow-main", main);
        }
        ui.openTab("luma.tab.variants", LumiBranchesScreen.class);
        ui.assertButtonStates(
                LumiBranchesScreen.class, "luma.action.variant_switch", 1, 1);
        ui.assertButtonStates(
                LumiBranchesScreen.class, "luma.action.delete_branch", 1, 1);
        ui.assertButtonStates(
                LumiBranchesScreen.class, "luma.action.merge_into_current", 1, 1);
        ui.assertButtonStates(
                LumiBranchesScreen.class, "luma.action.open_history", 2, 0);

        int delete = ui.firstActiveButton(
                LumiBranchesScreen.class, "luma.action.delete_branch");
        ui.pressButton(LumiBranchesScreen.class,
                "luma.action.delete_branch", delete, false);
        context.waitForScreen(LumiDeleteBranchScreen.class);
        ui.assertButton(
                LumiDeleteBranchScreen.class, "luma.action.delete_branch", false);
        ui.typeIntoFocusedTextBox(
                LumiDeleteBranchScreen.class, shortName(idea.value()));
        ui.assertButton(
                LumiDeleteBranchScreen.class, "luma.action.delete_branch", true);
        ui.closeScreen(LumiDeleteBranchScreen.class, LumiBranchesScreen.class);

        int merge = ui.firstActiveButton(
                LumiBranchesScreen.class, "luma.action.merge_into_current");
        ui.pressButton(LumiBranchesScreen.class,
                "luma.action.merge_into_current", merge, false);
        context.waitForScreen(LumiMergeScreen.class);
        ui.assertButton(LumiMergeScreen.class, "luma.action.preview", true);
        ui.pressUniqueButton(LumiMergeScreen.class, "luma.action.preview");
        ui.assertButton(
                LumiMergeScreen.class, "luma.action.merge_into_current", true);
        ui.assertButton(LumiMergeScreen.class, "luma.action.cancel", true);
        report.event("ui_contract", "branch_controls", "succeeded", 0, 0,
                "BRANCH-02 BRANCH-05..11 MERGE-01 MERGE-03");
        ui.closeScreen(LumiMergeScreen.class, LumiBranchesScreen.class);
        ui.closeScreen(LumiBranchesScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyCompare(
            LumiUiTestDriver ui, LumiBehaviorReport report) {
        ui.openTab("luma.tab.compare", LumiComparePickerScreen.class);
        ui.assertButtonCountEventually(
                LumiComparePickerScreen.class, "luma.compare.select_save", 6);
        ui.pressButton(
                LumiComparePickerScreen.class, "luma.compare.select_save", 0, false);
        ui.pressButton(
                LumiComparePickerScreen.class, "luma.compare.select_save", 3, false);
        ui.assertButton(
                LumiComparePickerScreen.class, "luma.action.see_changes", true);
        report.event("ui_contract", "compare_selection", "succeeded", 0, 0,
                "COMP-02 COMP-03 COMP-05");
        ui.closeScreen(LumiComparePickerScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyZones(
            ClientGameTestContext context,
            LumiUiTestDriver ui,
            LumiBehaviorReport report) {
        String name = "workflow-zone";
        ui.openTab("luma.tab.zones", LumiZonesScreen.class);
        ui.assertButton(LumiZonesScreen.class, "luma.zones.create_button", false);
        ui.typeIntoFocusedTextBox(LumiZonesScreen.class, name);
        ui.assertButton(LumiZonesScreen.class, "luma.zones.create_button", true);
        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.create_button");
        context.waitForScreen(LumiDashboardScreen.class);
        ui.awaitZone(name, true);

        ui.pressUniqueButton(LumiDashboardScreen.class, "luma.tab.zones");
        context.waitForScreen(LumiZoneDetailsScreen.class);
        ui.assertButton(LumiZoneDetailsScreen.class, "luma.zones.save_button", true);
        ui.assertButton(
                LumiZoneDetailsScreen.class, "luma.action.amend_version", false);
        ui.assertButton(
                LumiZoneDetailsScreen.class, "luma.action.see_changes", true);
        ui.assertButton(LumiZoneDetailsScreen.class, "luma.zones.leave", true);
        ui.assertButton(LumiZoneDetailsScreen.class,
                "luma.history.view_cards", true);
        ui.assertButton(LumiZoneDetailsScreen.class,
                "luma.history.view_graph", true);
        ui.pressUniqueButton(LumiZoneDetailsScreen.class, "luma.zones.leave");
        context.waitForScreen(LumiZonesScreen.class);
        ui.awaitZone(name, false);
        ui.assertButtonEventually(LumiZonesScreen.class, "luma.zones.enter", true);
        ui.assertButton(LumiZonesScreen.class, "luma.action.open_details", true);
        ui.assertButton(LumiZonesScreen.class, "luma.zones.delete", true);

        ui.pressUniqueButton(LumiZonesScreen.class, "luma.zones.delete");
        context.waitForScreen(LumiDeleteZoneScreen.class);
        ui.assertButton(
                LumiDeleteZoneScreen.class, "luma.zones.delete_confirm", false);
        ui.typeIntoFocusedTextBox(LumiDeleteZoneScreen.class, name);
        ui.assertButton(
                LumiDeleteZoneScreen.class, "luma.zones.delete_confirm", true);
        ui.assertButton(LumiDeleteZoneScreen.class, "luma.action.cancel", true);
        report.event("ui_contract", "zone_controls", "succeeded", 0, 0,
                "ZONE-01..07 ZONE-09..10");
        ui.closeScreen(LumiDeleteZoneScreen.class, LumiZonesScreen.class);
        ui.closeScreen(LumiZonesScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static String shortName(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
