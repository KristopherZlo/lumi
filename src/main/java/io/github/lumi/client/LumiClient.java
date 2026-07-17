package io.github.lumi.client;

import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.client.preview.ClientVersionPreviewCapture;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.onboarding.ClientOnboardingStateRepository;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.LumiSettingsScreen;
import io.github.lumi.client.ui.LumiUpdateScreen;
import io.github.lumi.client.ui.LumiOperationHud;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiDiagnosticsScreen;
import io.github.lumi.client.ui.LumiDeleteVersionScreen;
import io.github.lumi.client.ui.LumiDeletedVersionsScreen;
import io.github.lumi.client.ui.LumiBranchScreen;
import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiCompareScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiMoreScreen;
import io.github.lumi.client.ui.LumiOnboardingScreen;
import io.github.lumi.client.ui.LumiPackageScreen;
import io.github.lumi.client.ui.LumiPackageInspectionScreen;
import io.github.lumi.client.ui.LumiSpecialThanksScreen;
import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.client.ui.LumiWorkspacesScreen;
import io.github.lumi.client.ui.LumiZoneDetailsScreen;
import io.github.lumi.client.ui.LumiZoneRestoreScreen;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.BranchNameController;
import io.github.lumi.client.ui.SaveScreenController;
import io.github.lumi.client.ui.PackageScreenController;
import io.github.lumi.client.ui.ZoneScreenController;
import io.github.lumi.client.ui.ZoneDetailsController;
import io.github.lumi.client.ui.WorkspaceScreenController;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PackageInspectionPayload;
import io.github.lumi.telemetry.TelemetryService;
import io.github.lumi.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client entrypoint; retained UI controllers consume this single networking facade. */
public final class LumiClient implements ClientModInitializer {
    private static final ClientHistoryStore HISTORY = new ClientHistoryStore();
    private static final ClientCompareStore COMPARISONS = new ClientCompareStore();
    private static final ClientSelection SELECTION = new ClientSelection();
    private static final ClientOnboardingStateRepository ONBOARDING =
            new ClientOnboardingStateRepository();
    private static final TelemetryService TELEMETRY = TelemetryService.getInstance();
    private static final UpdateChecker UPDATE_CHECKER = UpdateChecker.createDefault();
    private static final ClientVersionPreviewStore PREVIEW_STORE =
            new ClientVersionPreviewStore();
    private static final ClientVersionPreviewCapture PREVIEW_CAPTURE =
            new ClientVersionPreviewCapture(PREVIEW_STORE);
    private static boolean onboardingShown;
    private static final LumiClientNetworking NETWORKING =
            new LumiClientNetworking(
                    HISTORY, COMPARISONS, LumiClient::acceptSnapshot,
                    PREVIEW_CAPTURE::accept,
                    LumiClient::showPackageInspection);

    @Override
    public void onInitializeClient() {
        NETWORKING.register();
        PREVIEW_CAPTURE.register();
        LumiHotkeys hotkeys = new LumiHotkeys(new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openDashboard() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiDashboardScreen(
                                client.screen, HISTORY, PREVIEW_STORE,
                                () -> LumiClient.openSave(client.screen,
                                        SaveScreenController.Intent.SAVE, ""),
                                () -> LumiClient.openSave(client.screen,
                                        SaveScreenController.Intent.AMEND,
                                        latestVersionMessage()),
                                LumiClient::openBranches,
                                LumiClient::openZones,
                                parent -> client.setScreen(new LumiPackageScreen(
                                        parent, new PackageScreenController(
                                                NETWORKING::exportPackage,
                                                NETWORKING::inspectPackage))),
                                LumiClient::openMore,
                                parent -> client.setScreen(new LumiSettingsScreen(
                                        parent, TELEMETRY)),
                                () -> {
                                    NETWORKING.refreshSnapshot();
                                    showFeedback("luma.hotkeys.pending_preview_help");
                                    client.setScreen(null);
                                },
                                NETWORKING::quickRollback,
                                version -> client.setScreen(new LumiRestoreScreen(
                                        client.screen,
                                        version.id(),
                                        version.message(),
                                        SELECTION.bounds(),
                                        (target, includeEntities) -> {
                                            if (includeEntities) {
                                                NETWORKING.restore(target);
                                            } else {
                                                NETWORKING.restoreWithoutEntities(target);
                                            }
                                        },
                                        NETWORKING::restoreArea)),
                                version -> client.setScreen(new LumiDeleteVersionScreen(
                                        client.screen, version, NETWORKING::deleteVersion)),
                                target -> client.setScreen(new LumiCompareScreen(
                                        client.screen,
                                        COMPARISONS,
                                        target.label(),
                                        () -> NETWORKING.compare(
                                                target.before(), target.after()),
                                        NETWORKING::cancelCompare))));
                    }

                    @Override public void openSave() {
                        Minecraft client = Minecraft.getInstance();
                        LumiClient.openSave(
                                client.screen, SaveScreenController.Intent.SAVE, "");
                    }

                    @Override public void openHotkeys() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiHotkeyScreen(
                                client.screen,
                                LumiHotkeys.shortcuts(client.options.keyMappings)));
                    }

                    @Override public boolean undoSelection() { return SELECTION.undo(); }
                    @Override public boolean redoSelection() { return SELECTION.redo(); }
                    @Override public void undo() { NETWORKING.undo(); }
                    @Override public void redo() { NETWORKING.redo(); }
                    @Override public void quickRollback() { NETWORKING.quickRollback(); }
                    @Override public void switchBranch(int slot) {
                        var snapshot = HISTORY.state().snapshot().orElseThrow(
                                () -> new IllegalStateException(
                                        "Lumi history has not synchronized yet"));
                        if (slot >= snapshot.branches().size()) {
                            throw new IllegalStateException(
                                    "No Lumi branch is bound to this number");
                        }
                        var branch = snapshot.branches().get(slot);
                        if (!branch.active()) {
                            NETWORKING.switchBranch(branch.name());
                        }
                    }
                }, LumiClient::showFeedback));
        hotkeys.register();
        new LumiSelectionTool(SELECTION, LumiClient::showFeedback).register();
        new LumiOperationHud(HISTORY).register();
        new LumiPendingChangeOverlay(
                HISTORY, COMPARISONS, NETWORKING::refreshSnapshot).register();
        LumiMod.LOGGER.info("Lumi V2 client initialized");
    }

    private static void showFeedback(String value) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Component message = value.startsWith("luma.")
                ? Component.translatable(value) : Component.literal(value);
        player.displayClientMessage(message, true);
    }

    private static void openZones(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiZonesScreen(
                parent, HISTORY, SELECTION::bounds,
                new ZoneScreenController(NETWORKING::createZone),
                zone -> openZoneDetails(client.screen, zone),
                NETWORKING::enterZone, NETWORKING::leaveZone));
    }

    private static void openWorkspaces(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiWorkspacesScreen(
                parent, HISTORY, SELECTION::bounds,
                new WorkspaceScreenController(NETWORKING::createWorkspace),
                NETWORKING::switchWorkspace));
    }

    private static void openBranches(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = HISTORY.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        client.setScreen(new LumiBranchesScreen(
                parent, snapshot.branches(),
                () -> client.setScreen(new LumiBranchScreen(
                        client.screen, currentBranch(),
                        new BranchNameController(NETWORKING::createBranch))),
                () -> client.setScreen(new LumiMergeScreen(
                        client.screen, snapshot.branchName(),
                        snapshot.branches(), NETWORKING::merge)),
                NETWORKING::switchBranch));
    }

    private static void openSave(
            Screen parent, SaveScreenController.Intent intent, String initialMessage) {
        Minecraft.getInstance().setScreen(new LumiSaveScreen(
                parent, HISTORY,
                new SaveScreenController(NETWORKING::save, NETWORKING::amend),
                NETWORKING::refreshSnapshot, intent, initialMessage,
                requestId -> PREVIEW_CAPTURE.request(
                        requestId, HISTORY.state().snapshot().orElseThrow())));
    }

    private static String latestVersionMessage() {
        return HISTORY.state().snapshot().stream()
                .flatMap(snapshot -> snapshot.versions().stream())
                .findFirst().map(HistorySnapshotPayload.Version::message).orElse("");
    }

    private static void openMore(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiMoreScreen(
                parent,
                () -> openWorkspaces(client.screen),
                () -> client.setScreen(new LumiDeletedVersionsScreen(
                        client.screen, HISTORY, NETWORKING::cleanupVersion)),
                () -> client.setScreen(new LumiOnboardingScreen(
                        client.screen, LumiClient::completeOnboarding)),
                () -> client.setScreen(new LumiHotkeyScreen(
                        client.screen,
                        LumiHotkeys.shortcuts(client.options.keyMappings))),
                () -> client.setScreen(new LumiSpecialThanksScreen(client.screen)),
                () -> client.setScreen(new LumiDiagnosticsScreen(client.screen, HISTORY)),
                () -> client.setScreen(new LumiSettingsScreen(parent, TELEMETRY)),
                () -> client.setScreen(new LumiUpdateScreen(
                        client.screen, UPDATE_CHECKER))));
    }

    private static void openZoneDetails(
            Screen zones, HistorySnapshotPayload.ZoneView zone) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiZoneDetailsScreen(
                zones, zone, new ZoneDetailsController(NETWORKING::saveZone),
                version -> client.setScreen(new LumiZoneRestoreScreen(
                        client.screen, zones, zone, version, NETWORKING::restoreZone)),
                target -> client.setScreen(new LumiCompareScreen(
                        client.screen, COMPARISONS, target.label(),
                        () -> NETWORKING.compareZone(
                                zone.id(), target.before(), target.after()),
                        NETWORKING::cancelCompare))));
    }

    private static void showRecovery(HistorySnapshotPayload snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (!snapshot.recoveryPending()
                || snapshot.operationActive()
                || client.screen instanceof LumiRecoveryScreen) {
            return;
        }
        client.setScreen(new LumiRecoveryScreen(
                client.screen, NETWORKING::resumeRecovery, NETWORKING::returnRecovery));
    }

    private static void acceptSnapshot(HistorySnapshotPayload snapshot) {
        showRecovery(snapshot);
        Minecraft client = Minecraft.getInstance();
        if (snapshot.recoveryPending() || snapshot.operationActive()
                || client.player == null || client.screen != null) {
            return;
        }
        if (!onboardingShown && !ONBOARDING.completed()) {
            onboardingShown = true;
            client.setScreen(new LumiOnboardingScreen(null, LumiClient::completeOnboarding));
            return;
        }
        showTelemetryNotice();
    }

    private static void completeOnboarding() {
        ONBOARDING.markCompleted();
        showTelemetryNotice();
    }

    private static void showTelemetryNotice() {
        var player = Minecraft.getInstance().player;
        if (player != null && TELEMETRY.consumeNotice()) {
            player.displayClientMessage(Component.translatable("luma.telemetry.notice"), false);
        }
    }

    private static void showPackageInspection(PackageInspectionPayload inspection) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof LumiPackageScreen packages)) {
            return;
        }
        client.setScreen(new LumiPackageInspectionScreen(
                packages, inspection,
                () -> NETWORKING.importPackage(inspection.requestId())));
    }

    private static String currentBranch() {
        String value = HISTORY.state().snapshot().orElseThrow(
                () -> new IllegalStateException("Lumi history has not synchronized yet"))
                .branchName();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    public static ClientHistoryStore history() {
        return HISTORY;
    }

    public static LumiClientNetworking networking() {
        return NETWORKING;
    }

    public static ClientSelection selection() {
        return SELECTION;
    }
}
