package io.github.lumi.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientNotificationStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.client.state.ClientSurvivalSettingsStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientBranchSlotStore;
import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.client.state.ClientSelection;
import io.github.lumi.client.state.ClientZoneOverlayStore;
import io.github.lumi.client.preview.ClientVersionPreviewCapture;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.onboarding.ClientOnboardingWorldStep;
import io.github.lumi.client.onboarding.ClientOnboardingStateRepository;
import io.github.lumi.client.onboarding.OnboardingController;
import io.github.lumi.client.onboarding.OnboardingEvent;
import io.github.lumi.client.ui.LumiSaveScreen;
import io.github.lumi.client.ui.LumiSettingsScreen;
import io.github.lumi.client.ui.LumiUpdateScreen;
import io.github.lumi.client.ui.LumiOperationHud;
import io.github.lumi.client.ui.LumiDashboardScreen;
import io.github.lumi.client.ui.LumiDiagnosticsScreen;
import io.github.lumi.client.ui.LumiDimensionsScreen;
import io.github.lumi.client.ui.LumiDimensionHistoryScreen;
import io.github.lumi.client.ui.LumiDeleteVersionScreen;
import io.github.lumi.client.ui.LumiDeleteZoneScreen;
import io.github.lumi.client.ui.LumiDeletedVersionsScreen;
import io.github.lumi.client.ui.LumiBranchScreen;
import io.github.lumi.client.ui.LumiBranchSlotScreen;
import io.github.lumi.client.ui.LumiBranchesScreen;
import io.github.lumi.client.ui.LumiComparePickerScreen;
import io.github.lumi.client.ui.LumiCleanupScreen;
import io.github.lumi.client.ui.LumiMergeScreen;
import io.github.lumi.client.ui.LumiMoreScreen;
import io.github.lumi.client.ui.LumiOnboardingScreen;
import io.github.lumi.client.ui.LumiPackageScreen;
import io.github.lumi.client.ui.LumiPackageInspectionScreen;
import io.github.lumi.client.ui.LumiSpecialThanksScreen;
import io.github.lumi.client.ui.LumiHotkeyScreen;
import io.github.lumi.client.ui.LumiZonesScreen;
import io.github.lumi.client.ui.LumiZoneDetailsScreen;
import io.github.lumi.client.ui.LumiZoneRestoreScreen;
import io.github.lumi.client.ui.LumiRecoveryScreen;
import io.github.lumi.client.ui.LumiRestoreScreen;
import io.github.lumi.client.ui.LumiVersionDetailsScreen;
import io.github.lumi.client.ui.BranchNameController;
import io.github.lumi.client.ui.SaveScreenController;
import io.github.lumi.client.ui.PackageScreenController;
import io.github.lumi.client.ui.ZoneScreenController;
import io.github.lumi.client.ui.ZoneHistoryActions;
import io.github.lumi.client.ui.VersionCompareController;
import io.github.lumi.domain.model.HudDisplayMode;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.CleanupResultPayload;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.PackageInspectionPayload;
import io.github.lumi.network.PartialRestorePlanPayload;
import io.github.lumi.telemetry.TelemetryService;
import io.github.lumi.update.UpdateChecker;
import io.github.lumi.update.ClientUpdatePreferenceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.GameType;

/** Client entrypoint; retained UI controllers consume this single networking facade. */
public final class LumiClient implements ClientModInitializer {
    private static final ClientHistoryStore HISTORY = new ClientHistoryStore();
    private static final ClientHistoryPageStore HISTORY_PAGES =
            new ClientHistoryPageStore();
    private static final ClientBranchSlotStore BRANCH_SLOTS =
            new ClientBranchSlotStore();
    private static final ClientCompareStore COMPARISONS = new ClientCompareStore();
    private static final ClientSelection SELECTION = new ClientSelection();
    private static final ClientZoneOverlayStore ZONE_OVERLAYS =
            new ClientZoneOverlayStore();
    private static final ClientPendingStatisticsStore PENDING_STATISTICS =
            new ClientPendingStatisticsStore();
    private static final ClientNotificationStore NOTIFICATIONS =
            new ClientNotificationStore();
    private static final ClientSurvivalSettingsStore SURVIVAL_SETTINGS =
            new ClientSurvivalSettingsStore();
    private static final ClientOnboardingStateRepository ONBOARDING =
            new ClientOnboardingStateRepository();
    private static final TelemetryService TELEMETRY = TelemetryService.getInstance();
    private static final UpdateChecker UPDATE_CHECKER = UpdateChecker.createDefault();
    private static final ClientUpdatePreferenceRepository UPDATE_PREFERENCES =
            new ClientUpdatePreferenceRepository();
    private static final ClientVersionPreviewStore PREVIEW_STORE =
            new ClientVersionPreviewStore();
    private static final ClientVersionPreviewCapture PREVIEW_CAPTURE =
            new ClientVersionPreviewCapture(PREVIEW_STORE);
    private static boolean onboardingShown;
    private static OnboardingController activeOnboarding;
    private static final LumiClientNetworking NETWORKING =
            new LumiClientNetworking(
                    HISTORY, HISTORY_PAGES, COMPARISONS, ZONE_OVERLAYS,
                    PENDING_STATISTICS, SURVIVAL_SETTINGS,
                    LumiClient::acceptSnapshot,
                    LumiClient::acceptOperationEvent,
                    LumiClient::acceptCompareResult,
                    LumiClient::showPackageInspection,
                    LumiClient::showCleanupResult,
                    LumiClient::showPartialRestorePlan);
    private static final ClientOnboardingWorldStep ONBOARDING_WORLD =
            new ClientOnboardingWorldStep(
                    HISTORY, NETWORKING::refreshSnapshot);
    private static final LumiZoneOverlay ZONE_OVERLAY =
            new LumiZoneOverlay(
                    ZONE_OVERLAYS, HISTORY, NETWORKING::requestZoneOverlay);

    @Override
    public void onInitializeClient() {
        NETWORKING.register();
        PREVIEW_CAPTURE.register();
        LumiHotkeys hotkeys = new LumiHotkeys(new HotkeyActionDispatcher(
                new HotkeyActionDispatcher.Actions() {
                    @Override public void openDashboard() {
                        Minecraft client = Minecraft.getInstance();
                        LumiClient.openDashboard(client.screen);
                    }

                    @Override public void openSave() {
                        Minecraft client = Minecraft.getInstance();
                        activeZone().ifPresentOrElse(
                                zone -> LumiClient.openZoneSave(
                                        client.screen, zone,
                                        SaveScreenController.Intent.SAVE, ""),
                                () -> LumiClient.openSave(
                                        client.screen,
                                        SaveScreenController.Intent.SAVE, ""));
                    }

                    @Override public void openHotkeys() {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new LumiHotkeyScreen(
                                client.screen,
                                LumiHotkeys.shortcuts(client.options.keyMappings)));
                    }

                    @Override public boolean undoSelection() {
                        return LumiSelectionTool.held(Minecraft.getInstance())
                                && SELECTION.undo();
                    }
                    @Override public boolean redoSelection() {
                        return LumiSelectionTool.held(Minecraft.getInstance())
                                && SELECTION.redo();
                    }
                    @Override public void undo() {
                        trackOnboardingOperation(
                                OnboardingEvent.OperationKind.UNDO,
                                NETWORKING.undo());
                    }
                    @Override public void redo() {
                        trackOnboardingOperation(
                                OnboardingEvent.OperationKind.REDO,
                                NETWORKING.redo());
                    }
                    @Override public String toggleCompareOverlay() {
                        return COMPARISONS.toggleVisibility()
                                .map(visible -> visible
                                        ? "luma.status.compare_overlay_enabled"
                                        : "luma.status.compare_overlay_hidden")
                                .orElse("luma.status.compare_failed");
                    }
                    @Override public void quickRollback() {
                        NETWORKING.quickRollback();
                    }
                    @Override public void switchBranch(int keyCode) {
                        var snapshot = HISTORY.state().snapshot().orElseThrow(
                                () -> new IllegalStateException(
                                        "Lumi history has not synchronized yet"));
                        var branch = BRANCH_SLOTS.branch(snapshot, keyCode)
                                .orElseThrow(() -> new IllegalStateException(
                                        "No Lumi branch is bound to this key"));
                        if (!branch.active()) {
                            NETWORKING.switchBranch(branch.name());
                        }
                    }
                }, LumiClient::showFeedback, LumiClient::hotkeysEnabled),
                () -> HISTORY.state().snapshot()
                        .map(BRANCH_SLOTS::keys).orElseGet(List::of),
                LumiClient::acceptOnboardingEvent);
        hotkeys.register();
        new LumiSelectionTool(
                SELECTION, HISTORY, LumiClient::showFeedback,
                NETWORKING::editActiveZone).register();
        new LumiSelectionOverlay(SELECTION).register();
        new LumiSelectionHud(SELECTION).register();
        ZONE_OVERLAY.register();
        new LumiOperationHud(
                HISTORY, PENDING_STATISTICS, NOTIFICATIONS).register();
        new LumiPendingChangeOverlay(
                HISTORY, COMPARISONS, NETWORKING::refreshSnapshot).register();
        ONBOARDING_WORLD.register();
        LumiMod.LOGGER.info("Lumi V2 client initialized");
    }

    private static LumiDashboardScreen dashboard(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        return new LumiDashboardScreen(
                parent, HISTORY, PREVIEW_STORE,
                HISTORY_PAGES, NETWORKING::requestHistoryPage,
                NETWORKING::requestHistoryPage,
                PENDING_STATISTICS,
                NETWORKING::requestPendingStatistics,
                () -> LumiClient.openSave(client.screen,
                        SaveScreenController.Intent.SAVE, ""),
                message -> LumiClient.openSave(client.screen,
                        SaveScreenController.Intent.AMEND, message),
                LumiClient::openBranches,
                LumiClient::openZones,
                LumiClient::openPackages,
                LumiClient::openMore,
                screen -> client.setScreen(new LumiSettingsScreen(
                        screen, HISTORY, TELEMETRY,
                        NETWORKING::updateWorkspaceSettings,
                        SURVIVAL_SETTINGS,
                        NETWORKING::requestSurvivalSettings,
                        NETWORKING::updateSurvivalSettings)),
                () -> {
                    NETWORKING.refreshSnapshot();
                    showFeedback("luma.hotkeys.pending_preview_help");
                    client.setScreen(null);
                },
                NETWORKING::quickRollback,
                version -> openVersionDetails(client.screen, version),
                version -> openRestore(client.screen, version),
                version -> openBranchAt(client.screen, version),
                NETWORKING::updateVersionTags,
                LumiClient::showCompareChanges,
                COMPARISONS::highlightVisible,
                COMPARISONS::toggleVisibility);
    }

    private static void openDashboard(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        LumiDashboardScreen dashboard = dashboard(parent);
        client.setScreen(dashboard);
        activeZone().ifPresent(zone -> openZones(dashboard));
    }

    private static void showFeedback(String value) {
        showFeedback(value, "luma.status.survival_disabled".equals(value)
                ? ChatFormatting.RED : ChatFormatting.WHITE);
    }

    private static void showFeedback(String value, ChatFormatting color) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        MutableComponent message = value.startsWith("luma.")
                ? Component.translatable(value) : Component.literal(value);
        MutableComponent styled = message.withStyle(color);
        Integer rgb = color.getColor();
        NOTIFICATIONS.add(styled,
                0xff000000 | (rgb == null ? 0xf0f3f6 : rgb));
        player.displayClientMessage(styled, true);
    }

    private static void acceptOperationEvent(OperationEventPayload event) {
        PREVIEW_CAPTURE.accept(event);
        if (Minecraft.getInstance().screen instanceof LumiDeleteVersionScreen delete
                && delete.accept(event)) {
            HISTORY_PAGES.invalidateDimension(event.dimensionId());
            NETWORKING.refreshSnapshot();
        }
        if (event.state() != OperationEventPayload.State.ACCEPTED
                && event.state() != OperationEventPayload.State.PROGRESS) {
            acceptOnboardingEvent(new OnboardingEvent.OperationCompleted(
                    event.requestId(),
                    event.state() == OperationEventPayload.State.SUCCEEDED));
            showFeedback(event.message(), eventColor(event.state()));
        }
    }

    static ChatFormatting eventColor(OperationEventPayload.State state) {
        return switch (state) {
            case SUCCEEDED -> ChatFormatting.GREEN;
            case FAILED -> ChatFormatting.RED;
            case CANCELLED -> ChatFormatting.YELLOW;
            case RETURNED -> ChatFormatting.GOLD;
            case DEGRADED -> ChatFormatting.DARK_RED;
            case ACCEPTED, PROGRESS -> ChatFormatting.WHITE;
        };
    }

    private static void openZones(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        LumiZonesScreen zones = new LumiZonesScreen(
                parent, HISTORY,
                new ZoneScreenController(NETWORKING::createZone),
                zone -> openZoneDetails(client.screen, zone),
                zone -> client.setScreen(new LumiDeleteZoneScreen(
                        client.screen, zone, NETWORKING::deleteZone)),
                ZONE_OVERLAY::label, ZONE_OVERLAY::cycle,
                NETWORKING::enterZone, NETWORKING::leaveZone);
        client.setScreen(zones);
        activeZone().ifPresent(zone -> openZoneDetails(zones, zone));
    }

    private static Optional<HistorySnapshotPayload.ZoneView> activeZone() {
        return HISTORY.state().snapshot().stream()
                .flatMap(snapshot -> snapshot.zones().stream())
                .filter(HistorySnapshotPayload.ZoneView::active)
                .findFirst();
    }

    private static void openDimensions(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiDimensionsScreen(
                parent, LumiClient::visibleDimensions,
                () -> HISTORY.state().snapshot().orElseThrow().dimensionId(),
                dimension -> openDimensionHistory(client.screen, dimension)));
    }

    private static java.util.List<String> visibleDimensions() {
        var dimensions = new java.util.TreeSet<String>();
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().levels().forEach(
                    key -> dimensions.add(key.identifier().toString()));
        }
        HISTORY.state().snapshot().stream()
                .map(HistorySnapshotPayload::dimensionId)
                .forEach(dimensions::add);
        return java.util.List.copyOf(dimensions);
    }

    private static void openDimensionHistory(Screen parent, String dimension) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiDimensionHistoryScreen(
                parent, dimension, HISTORY_PAGES, PREVIEW_STORE,
                NETWORKING::requestDimensionHistoryPage,
                version -> openDimensionVersionDetails(
                        client.screen, dimension, version)));
    }

    private static void openBranches(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = HISTORY.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        var activeZone = snapshot.zones().stream()
                .filter(HistorySnapshotPayload.ZoneView::active)
                .findFirst();
        client.setScreen(new LumiBranchesScreen(
                parent, activeZone, snapshot.branches(),
                new BranchNameController(name -> activeZone.ifPresentOrElse(
                        zone -> NETWORKING.createBranchAt(
                                name, zone.versions().stream().findFirst()
                                        .orElseThrow(() -> new IllegalStateException(
                                                "Save the active zone before creating a branch"))
                                        .id()),
                        () -> NETWORKING.createBranch(name))),
                source -> client.setScreen(new LumiMergeScreen(
                        client.screen, snapshot,
                        snapshot.branches().stream()
                                .filter(branch -> branch.active()
                                        || branch.name().equals(source))
                                .toList(),
                        PREVIEW_STORE, NETWORKING::merge)),
                branch -> {
                    if (parent instanceof LumiDashboardScreen dashboard) {
                        dashboard.openBranchHistory(branch);
                    }
                },
                NETWORKING::switchBranch,
                NETWORKING::deleteBranch,
                branch -> client.setScreen(new LumiBranchSlotScreen(
                        client.screen, snapshot, branch, BRANCH_SLOTS,
                        LumiClient::showFeedback)),
                branch -> branchBinding(snapshot, branch)));
    }

    private static String branchBinding(
            HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.Branch branch) {
        String modifier = LumiHotkeys.bindingLabel(
                Minecraft.getInstance().options.keyMappings,
                "key.lumi.action_modifier");
        return BRANCH_SLOTS.keyCode(snapshot, branch.name()).stream()
                .mapToObj(code -> InputConstants.Type.KEYSYM
                        .getOrCreate(code).getDisplayName().getString())
                .map(key -> "[" + modifier + "]+[" + key + "]")
                .findFirst()
                .orElseGet(() -> Component.translatable(
                        "luma.ideas.switch_key_unassigned").getString());
    }

    private static void openPackages(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        var snapshot = HISTORY.state().snapshot().orElseThrow(
                () -> new IllegalStateException(
                        "Lumi history has not synchronized yet"));
        client.setScreen(new LumiPackageScreen(
                parent,
                new PackageScreenController(
                        NETWORKING::exportPackage, NETWORKING::inspectPackage),
                ClientPackageAccess.integrated(), snapshot.branches(),
                NETWORKING::switchBranch,
                source -> client.setScreen(new LumiMergeScreen(
                        client.screen, snapshot,
                        snapshot.branches().stream()
                                .filter(branch -> branch.active()
                                        || branch.name().equals(source))
                                .toList(), PREVIEW_STORE, NETWORKING::merge)),
                NETWORKING::deleteBranch));
    }

    private static void openSave(
            Screen parent, SaveScreenController.Intent intent, String initialMessage) {
        openSave(parent, intent, initialMessage, ignored -> { });
    }

    private static void openSave(
            Screen parent,
            SaveScreenController.Intent intent,
            String initialMessage,
            Consumer<UUID> accepted) {
        Minecraft.getInstance().setScreen(new LumiSaveScreen(
                parent, HISTORY,
                new SaveScreenController(NETWORKING::save, NETWORKING::amend),
                NETWORKING::refreshSnapshot, intent, initialMessage,
                requestId -> PREVIEW_CAPTURE.request(
                        requestId, HISTORY.state().snapshot().orElseThrow()),
                accepted));
    }

    private static void openZoneSave(
            Screen parent,
            HistorySnapshotPayload.ZoneView zone,
            SaveScreenController.Intent intent,
            String initialMessage) {
        Minecraft.getInstance().setScreen(new LumiSaveScreen(
                parent, HISTORY,
                new SaveScreenController(
                        (message, tags) -> NETWORKING.saveZone(
                                zone.id(), message, tags),
                        (message, tags) -> NETWORKING.amendZone(
                                zone.id(), message, tags)),
                NETWORKING::refreshSnapshot, intent, initialMessage,
                requestId -> PREVIEW_CAPTURE.request(
                        requestId, HISTORY.state().snapshot().orElseThrow()),
                ignored -> { }, LumiSaveScreen.Scope.ZONE));
    }

    private static void openVersionDetails(
            Screen parent, HistorySnapshotPayload.Version version) {
        Minecraft client = Minecraft.getInstance();
        HistorySnapshotPayload snapshot = HISTORY.state().snapshot().orElseThrow();
        List<HistorySnapshotPayload.Version> versions = HISTORY_PAGES.page(
                        snapshot.dimensionId(), snapshot.workspaceId(),
                        new io.github.lumi.domain.model.BranchName(snapshot.branchName()),
                        Optional.empty())
                .map(io.github.lumi.network.HistoryPagePayload::versions)
                .orElse(snapshot.versions());
        int index = versions.indexOf(version);
        var compare = new VersionCompareController()
                .target(versions, index)
                .map(target -> (Runnable) () -> showCompareChanges(target));
        boolean idle = !snapshot.operationActive();
        var createBranch = idle ? Optional.of((Runnable) () ->
                openBranchAt(client.screen, version))
                : Optional.<Runnable>empty();
        client.setScreen(new LumiVersionDetailsScreen(
                parent, snapshot.dimensionId(), version, PREVIEW_STORE,
                () -> openRestore(client.screen, version), compare,
                createBranch,
                () -> openDelete(client.screen, version),
                tags -> NETWORKING.updateVersionTags(version.id(), tags),
                name -> NETWORKING.renameVersion(version.id(), name)));
    }

    private static void openDimensionVersionDetails(
            Screen parent,
            String dimensionId,
            HistorySnapshotPayload.Version version) {
        Minecraft.getInstance().setScreen(new LumiVersionDetailsScreen(
                parent, dimensionId, version, PREVIEW_STORE,
                () -> { }, Optional.empty(), Optional.empty(),
                () -> { }, ignored -> { }, ignored -> { },
                true));
    }

    private static void openBranchAt(
            Screen parent, HistorySnapshotPayload.Version version) {
        Minecraft.getInstance().setScreen(new LumiBranchScreen(
                parent, version.message(),
                new BranchNameController(name ->
                        NETWORKING.createBranchAt(name, version.id()))));
    }

    private static void openRestore(
            Screen parent, HistorySnapshotPayload.Version version) {
        boolean includeEntities = HISTORY.state().snapshot().orElseThrow()
                .workspaces().stream()
                .filter(HistorySnapshotPayload.WorkspaceView::active)
                .findFirst()
                .map(HistorySnapshotPayload.WorkspaceView::includeEntitiesOnRestore)
                .orElse(true);
        Minecraft.getInstance().setScreen(new LumiRestoreScreen(
                parent, version.id(), version.message(),
                includeEntities
                        ? NETWORKING::restore
                        : NETWORKING::restoreWithoutEntities,
                LumiSelectionTool.held(Minecraft.getInstance())
                        ? SELECTION.bounds() : Optional.empty(),
                NETWORKING::previewRestoreArea,
                NETWORKING::applyRestoreArea,
                requestId -> trackOnboardingOperation(
                        OnboardingEvent.OperationKind.RESTORE, requestId)));
    }

    private static void openDelete(
            Screen parent, HistorySnapshotPayload.Version version) {
        Minecraft.getInstance().setScreen(new LumiDeleteVersionScreen(
                parent, version, NETWORKING::deleteVersion));
    }

    private static void showCompareChanges(VersionCompareController.Target target) {
        startCompare(() -> NETWORKING.compare(target.before(), target.after()));
    }

    private static void showZoneCompareChanges(
            java.util.UUID zoneId, VersionCompareController.Target target) {
        startCompare(() -> NETWORKING.compareZone(
                zoneId, target.before(), target.after()));
    }

    private static void startCompare(Runnable request) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(null);
        try {
            request.run();
            showFeedback("luma.status.compare_loading", ChatFormatting.YELLOW);
        } catch (RuntimeException failed) {
            showFeedback(failed.getMessage() == null
                    ? "luma.status.compare_failed" : failed.getMessage(),
                    ChatFormatting.RED);
        }
    }

    private static void openMore(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LumiMoreScreen(
                parent,
                () -> openDimensions(client.screen),
                () -> client.setScreen(new LumiDeletedVersionsScreen(
                        client.screen, HISTORY,
                        NETWORKING::restoreDeletedVersion,
                        NETWORKING::cleanupVersion)),
                () -> openPackages(client.screen),
                () -> openOnboarding(client.screen),
                () -> client.setScreen(new LumiHotkeyScreen(
                        client.screen,
                        LumiHotkeys.shortcuts(client.options.keyMappings))),
                () -> client.setScreen(new LumiSpecialThanksScreen(client.screen)),
                () -> client.setScreen(new LumiDiagnosticsScreen(client.screen, HISTORY)),
                () -> client.setScreen(new LumiUpdateScreen(
                        client.screen, UPDATE_CHECKER, UPDATE_PREFERENCES)),
                () -> client.setScreen(new LumiCleanupScreen(
                        client.screen,
                        NETWORKING::inspectCleanup, NETWORKING::applyCleanup))));
    }

    private static void openZoneDetails(
            Screen zones, HistorySnapshotPayload.ZoneView zone) {
        Minecraft client = Minecraft.getInstance();
        HistorySnapshotPayload snapshot =
                HISTORY.state().snapshot().orElseThrow();
        client.setScreen(new LumiZoneDetailsScreen(
                zones, snapshot, zone,
                HISTORY_PAGES, NETWORKING::requestHistoryPage,
                PENDING_STATISTICS, NETWORKING::requestPendingStatistics,
                PREVIEW_STORE,
                new ZoneHistoryActions(
                        version -> openZoneVersionDetails(
                                client.screen, zones, zone, version),
                        version -> client.setScreen(new LumiZoneRestoreScreen(
                                client.screen, zones, zone, version,
                                NETWORKING::restoreZone)),
                        version -> openBranchAt(client.screen, version),
                        NETWORKING::updateVersionTags),
                () -> openZoneSave(
                        client.screen, zone, SaveScreenController.Intent.SAVE, ""),
                () -> openZoneSave(
                        client.screen, zone, SaveScreenController.Intent.AMEND,
                        zone.versions().isEmpty()
                                ? "" : zone.versions().getFirst().message()),
                () -> {
                    NETWORKING.refreshSnapshot();
                    showFeedback("luma.hotkeys.pending_preview_help");
                }, () -> NETWORKING.leaveZone(zone.id())));
    }

    private static void openZoneVersionDetails(
            Screen zoneDetails,
            Screen zones,
            HistorySnapshotPayload.ZoneView zone,
            HistorySnapshotPayload.Version version) {
        Minecraft client = Minecraft.getInstance();
        HistorySnapshotPayload snapshot =
                HISTORY.state().snapshot().orElseThrow();
        var compare = new VersionCompareController()
                .target(List.of(version), 0)
                .map(target -> (Runnable) () ->
                        showZoneCompareChanges(zone.id(), target));
        boolean idle = !snapshot.operationActive();
        client.setScreen(new LumiVersionDetailsScreen(
                zoneDetails, snapshot.dimensionId(), version, PREVIEW_STORE,
                () -> client.setScreen(new LumiZoneRestoreScreen(
                        client.screen, zones, zone, version,
                        NETWORKING::restoreZone)),
                compare,
                idle ? Optional.of((Runnable) () ->
                        openBranchAt(client.screen, version))
                        : Optional.empty(),
                () -> openDelete(client.screen, version),
                tags -> NETWORKING.updateVersionTags(version.id(), tags),
                name -> NETWORKING.renameVersion(version.id(), name)));
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
        BRANCH_SLOTS.synchronize(snapshot);
        if (snapshot.workspaces().stream()
                .filter(HistorySnapshotPayload.WorkspaceView::active)
                .anyMatch(workspace ->
                        workspace.hudDisplayMode() != HudDisplayMode.GUI)) {
            NOTIFICATIONS.clear();
        }
        if (snapshot.pendingKeys() == 0) {
            PENDING_STATISTICS.clear();
        } else if (!snapshot.operationActive()
                && PENDING_STATISTICS.needsRequest(snapshot)) {
            NETWORKING.requestPendingStatistics();
        }
        if (SURVIVAL_SETTINGS.needsRequest()) {
            NETWORKING.requestSurvivalSettings();
        }
        showRecovery(snapshot);
        Minecraft client = Minecraft.getInstance();
        if (snapshot.recoveryPending() || snapshot.operationActive()
                || client.player == null || client.screen != null) {
            return;
        }
        if (!onboardingShown && !ONBOARDING.completed()) {
            onboardingShown = true;
            openOnboarding(null);
            return;
        }
        showTelemetryNotice();
    }

    private static void completeOnboarding() {
        ONBOARDING.markCompleted();
        activeOnboarding = null;
        showTelemetryNotice();
    }

    private static boolean hotkeysEnabled() {
        Minecraft client = Minecraft.getInstance();
        return client.gameMode == null
                || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || SURVIVAL_SETTINGS.snapshot()
                        .map(ClientSurvivalSettingsStore.Snapshot::enabled)
                        .orElse(false);
    }

    private static void openOnboarding(Screen returnScreen) {
        if (activeOnboarding == null || activeOnboarding.completed()) {
            activeOnboarding = new OnboardingController();
        }
        showOnboarding(returnScreen, returnScreen, activeOnboarding);
    }

    private static void showOnboarding(
            Screen returnScreen,
            Screen background,
            OnboardingController controller) {
        Minecraft client = Minecraft.getInstance();
        LumiOnboardingScreen.Actions actions =
                new LumiOnboardingScreen.Actions(
                        activeController -> ONBOARDING_WORLD.start(
                                activeController,
                                resumed -> showOnboarding(
                                        returnScreen, null, resumed)),
                        (parent, saved) -> openSave(
                                parent, SaveScreenController.Intent.SAVE,
                                "", saved),
                        LumiClient::dashboard,
                        LumiClient::completeOnboarding);
        client.setScreen(new LumiOnboardingScreen(
                returnScreen, background, controller, actions));
    }

    private static void acceptOnboardingEvent(OnboardingEvent event) {
        if (ONBOARDING_WORLD.accept(event)) return;
        if (Minecraft.getInstance().screen instanceof LumiOnboardingScreen screen) {
            screen.accept(event);
        }
    }

    private static void trackOnboardingOperation(
            OnboardingEvent.OperationKind operation, UUID requestId) {
        OnboardingEvent event = new OnboardingEvent.OperationStarted(
                operation, requestId);
        if (ONBOARDING_WORLD.accept(event)) return;
        if (activeOnboarding != null && !activeOnboarding.completed()) {
            activeOnboarding.handle(event);
        }
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

    private static void acceptCompareResult(
            io.github.lumi.network.CompareResultPayload result) {
        if (!result.complete()) {
            return;
        }
        if (!result.error().isEmpty()) {
            showFeedback(result.error(), ChatFormatting.RED);
        } else {
            boolean empty = result.changedBlocks() == 0
                    && result.changedEntityChunks() == 0;
            showFeedback(empty
                    ? "luma.status.compare_no_changes"
                    : "luma.status.compare_ready",
                    empty ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        }
    }

    private static void showCleanupResult(CleanupResultPayload result) {
        if (Minecraft.getInstance().screen instanceof LumiCleanupScreen cleanup) {
            cleanup.accept(result);
        }
    }

    private static void showPartialRestorePlan(PartialRestorePlanPayload result) {
        if (Minecraft.getInstance().screen instanceof LumiRestoreScreen restore) {
            restore.accept(result);
        }
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
