package io.github.lumi.gametest;

import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiCleanupScreen;
import io.github.lumi.client.ui.LumiComparePickerScreen;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiDeletedVersionsScreen;
import io.github.lumi.client.ui.LumiDiagnosticsScreen;
import io.github.lumi.client.ui.LumiDimensionsScreen;
import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiMoreScreen;
import io.github.lumi.client.ui.LumiOnboardingScreen;
import io.github.lumi.client.ui.LumiPackageScreen;
import io.github.lumi.client.ui.LumiSettingsScreen;
import io.github.lumi.client.ui.LumiSpecialThanksScreen;
import io.github.lumi.client.ui.LumiUpdateScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

/** In-game smoke contract for clean-state controls and every secondary page. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiUiContractClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.UI)
                || LumiHistoryBenchmarkConfig.enabled()) return;
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.run(
                    context, "ui-contract", (test, world, report) -> {
                LumiUiTestDriver ui = new LumiUiTestDriver(test);
                ui.completeOnboardingIfShown();
                ui.awaitHistory();
                verifyCleanHistory(ui, report);
                verifyPrimaryPages(ui, report);
                verifyMorePages(ui, report);
            });
        }
    }

    private static void verifyCleanHistory(
            LumiUiTestDriver ui, LumiBehaviorReport report) {
        ui.openDashboard();
        ui.assertButton(LumiDashboardScreen.class,
                "luma.action.save_build", false);
        ui.assertButton(LumiDashboardScreen.class,
                "luma.action.amend_version", false);
        ui.assertButton(LumiDashboardScreen.class,
                "luma.action.see_changes", true);
        ui.assertButton(LumiDashboardScreen.class,
                "key.lumi.quick_rollback", true);
        ui.assertButton(LumiDashboardScreen.class,
                "luma.history.view_cards", true);
        ui.assertButton(LumiDashboardScreen.class,
                "luma.history.view_graph", true);
        ui.assertButtonEventually(LumiDashboardScreen.class,
                "luma.action.restore", true);
        ui.assertButtonEventually(LumiDashboardScreen.class,
                "luma.action.open_details", true);
        ui.assertButtonEventually(LumiDashboardScreen.class,
                "luma.action.create_idea", true);
        ui.assertButtonEventually(LumiDashboardScreen.class,
                "luma.action.edit_tags", false);
        for (String tab : new String[] {
                "luma.tab.history", "luma.tab.zones", "luma.tab.variants",
                "luma.tab.compare", "luma.action.settings", "luma.action.more"
        }) {
            ui.assertButton(LumiDashboardScreen.class, tab, true);
        }
        report.event("ui_contract", "clean_history", "succeeded", 0, 0,
                "HIST-01..05 HIST-09..12 NAV-01..06");
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static void verifyPrimaryPages(
            LumiUiTestDriver ui, LumiBehaviorReport report) {
        ui.openTab("luma.tab.zones", LumiZonesScreen.class);
        ui.assertButton(LumiZonesScreen.class,
                "luma.zones.create_button", false);
        ui.assertButton(LumiZonesScreen.class,
                "luma.zones.render_focused", true);
        ui.assertButtonCount(LumiZonesScreen.class,
                "luma.action.open_details", 0);
        ui.closeScreen(LumiZonesScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);

        ui.openTab("luma.tab.variants", LumiBranchesScreen.class);
        ui.assertButton(LumiBranchesScreen.class,
                "luma.action.variant_create", false);
        ui.assertButton(LumiBranchesScreen.class,
                "luma.action.variant_switch", false);
        ui.assertButton(LumiBranchesScreen.class,
                "luma.action.open_history", true);
        ui.assertButton(LumiBranchesScreen.class,
                "luma.action.delete_branch", false);
        ui.assertButton(LumiBranchesScreen.class,
                "luma.action.merge_into_current", false);
        ui.closeScreen(LumiBranchesScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);

        ui.openTab("luma.tab.compare", LumiComparePickerScreen.class);
        ui.assertButtonCountEventually(LumiComparePickerScreen.class,
                "luma.compare.select_save", 2);
        ui.assertButton(LumiComparePickerScreen.class,
                "luma.action.see_changes", false);
        ui.closeScreen(LumiComparePickerScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);

        ui.openTab("luma.action.settings", LumiSettingsScreen.class);
        ui.assertButton(LumiSettingsScreen.class,
                "luma.settings.show_hidden_commits", true);
        ui.assertButton(LumiSettingsScreen.class,
                "luma.settings.restore_entities", true);
        ui.assertButton(LumiSettingsScreen.class,
                "luma.settings.preview_generation", true);
        ui.assertButton(LumiSettingsScreen.class,
                "luma.settings.workspace_hud", true);
        ui.closeScreen(LumiSettingsScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
        report.event("ui_contract", "primary_pages", "succeeded", 0, 0,
                "ZONE-01..03 BRANCH-01..02 BRANCH-05..10 COMP-01 COMP-05 SETTING-01..04");
    }

    private static void verifyMorePages(
            LumiUiTestDriver ui, LumiBehaviorReport report) {
        ui.openTab("luma.action.more", LumiMoreScreen.class);
        openAndClose(ui, "luma.action.dimensions",
                LumiDimensionsScreen.class, LumiMoreScreen.class);
        openAndClose(ui, "luma.more.deleted_saves_title",
                LumiDeletedVersionsScreen.class, LumiMoreScreen.class);

        ui.pressUniqueButtonAfterScrolling(
                LumiMoreScreen.class, "luma.tab.import_export");
        ui.requireScreen(LumiPackageScreen.class);
        ui.assertButton(LumiPackageScreen.class,
                "luma.action.export_package", false);
        ui.assertButton(LumiPackageScreen.class,
                "luma.action.import_package", false);
        ui.assertButton(LumiPackageScreen.class,
                "luma.action.open_packages_folder", true);
        ui.assertButtonStartingWith(LumiPackageScreen.class,
                "luma.share.include_previews", true);
        ui.closeScreen(LumiPackageScreen.class, LumiMoreScreen.class);

        ui.pressUniqueButtonAfterScrolling(
                LumiMoreScreen.class, "luma.more.onboarding_title");
        ui.requireScreen(LumiOnboardingScreen.class);
        ui.assertButton(LumiOnboardingScreen.class, "luma.action.back", false);
        ui.assertButtonCount(LumiOnboardingScreen.class, "luma.action.skip", 0);
        ui.assertButton(LumiOnboardingScreen.class, "luma.action.start", true);
        ui.assertButton(LumiOnboardingScreen.class, "luma.action.close", true);
        ui.closeScreen(LumiOnboardingScreen.class, LumiMoreScreen.class);

        openAndClose(ui, "luma.hotkeys.title",
                LumiHotkeyScreen.class, LumiMoreScreen.class);
        openAndClose(ui, "luma.more.special_thanks_title",
                LumiSpecialThanksScreen.class, LumiMoreScreen.class);
        openAndClose(ui, "luma.action.open_diagnostics",
                LumiDiagnosticsScreen.class, LumiMoreScreen.class);
        openAndClose(ui, "luma.action.check_updates",
                LumiUpdateScreen.class, LumiMoreScreen.class);

        ui.pressUniqueButtonAfterScrolling(
                LumiMoreScreen.class, "luma.action.open_cleanup");
        ui.requireScreen(LumiCleanupScreen.class);
        ui.assertButton(LumiCleanupScreen.class,
                "luma.action.inspect_unused_files", true);
        ui.assertButton(LumiCleanupScreen.class,
                "luma.action.clean_up", false);
        ui.assertButton(LumiCleanupScreen.class,
                "luma.action.cancel", true);
        ui.closeScreen(LumiCleanupScreen.class, LumiMoreScreen.class);

        ui.assertButton(LumiMoreScreen.class,
                "luma.action.reset_contextual_hints", true);
        report.event("ui_contract", "more_pages", "succeeded", 0, 0,
                "MORE-01..11 PACKAGE-01..06 CLEAN-01..04 ONBOARD-01..04");
        ui.closeScreen(LumiMoreScreen.class, LumiDashboardScreen.class);
        ui.closeScreen(LumiDashboardScreen.class, null);
    }

    private static <T extends Screen> void openAndClose(
            LumiUiTestDriver ui,
            String action,
            Class<T> target,
            Class<? extends Screen> parent) {
        ui.pressUniqueButtonAfterScrolling(LumiMoreScreen.class, action);
        ui.requireScreen(target);
        ui.closeScreen(target, parent);
    }
}
