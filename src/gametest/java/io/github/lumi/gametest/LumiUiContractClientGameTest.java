package io.github.lumi.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.LumiClient;
import io.github.lumi.client.onboarding.ClientOnboardingStateRepository;
import io.github.lumi.client.onboarding.OnboardingController;
import io.github.lumi.client.onboarding.OnboardingTour;
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
import io.github.lumi.network.HistorySnapshotPayload;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
                verifyOnboarding(test, ui, report);
                verifyCleanHistory(ui, report);
                verifyPrimaryPages(ui, report);
                verifyMorePages(ui, report);
            });
        }
    }

    private static void verifyOnboarding(
            ClientGameTestContext context, LumiUiTestDriver ui,
            LumiBehaviorReport report) throws IOException {
        var preference = FabricLoader.getInstance().getConfigDir().resolve("lumi-onboarding");
        byte[] savedPreference = Files.exists(preference) ? Files.readAllBytes(preference) : null;
        Field shown = field(LumiClient.class, "onboardingShown");
        Field pending = field(LumiClient.class, "initialUiPending");
        Field active = field(LumiClient.class, "activeOnboarding");
        Object[] saved = context.computeOnClient(client -> {
            try {
                return new Object[]{shown.get(null), pending.get(null), active.get(null)};
            } catch (IllegalAccessException failed) { throw new AssertionError(failed); }
        });
        Screen welcome = context.computeOnClient(client ->
                new Screen(Component.literal("Another mod's welcome")) { });
        try {
            Files.createDirectories(preference.getParent());
            Files.writeString(preference, "0\n");
            var snapshotReceiver = LumiClient.class.getDeclaredMethod(
                    "acceptSnapshot", HistorySnapshotPayload.class);
            snapshotReceiver.setAccessible(true);
            context.runOnClient(client -> {
                try {
                    shown.setBoolean(null, false);
                    active.set(null, null);
                    client.setScreen(welcome);
                    snapshotReceiver.invoke(null, LumiClient.history().state().snapshot().orElseThrow());
                } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
            });
            for (int tick = 0; tick < 5; tick++) {
                context.waitTick();
                if (!context.computeOnClient(client -> client.screen == welcome)) {
                    throw new AssertionError("Onboarding replaced another mod's welcome");
                }
            }
            context.runOnClient(client -> client.setScreen(null));
            context.waitForScreen(LumiOnboardingScreen.class);
            Field tourField = field(OnboardingController.class, "tour");
            Field page = field(OnboardingTour.class, "index");
            context.runOnClient(client -> {
                try {
                    page.setInt(tourField.get(active.get(null)), OnboardingTour.pageCount() - 1);
                    client.setScreen(client.screen);
                } catch (IllegalAccessException failed) { throw new AssertionError(failed); }
            });
            context.getInput().holdAlt();
            try {
                context.getInput().pressKey(InputConstants.KEY_I);
                context.waitForScreen(LumiHotkeyScreen.class);
            } finally {
                context.getInput().releaseAlt();
            }
            if (!new ClientOnboardingStateRepository().completed()) {
                throw new AssertionError("Final hotkey did not persist onboarding completion");
            }
            ui.closeScreen(LumiHotkeyScreen.class, null);
            report.event("gate", "onboarding", "succeeded", 0, 0,
                    "Deferred behind another screen; final Alt+I opened help and completed once");
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        } finally {
            context.runOnClient(client -> {
                try {
                    shown.set(null, saved[0]);
                    pending.set(null, saved[1]);
                    active.set(null, saved[2]);
                } catch (IllegalAccessException failed) { throw new AssertionError(failed); }
            });
            if (savedPreference == null) Files.deleteIfExists(preference);
            else Files.write(preference, savedPreference);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failed) {
            throw new AssertionError(failed);
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
