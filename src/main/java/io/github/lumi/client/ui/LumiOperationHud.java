package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientNotificationStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.client.state.HighlightPreparationProgress;
import io.github.lumi.domain.model.HudDisplayMode;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

/** Compact HUD backed by immutable server events and local highlight progress. */
public final class LumiOperationHud {
    static final Identifier ID =
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "operation_hud");
    private static final int SCREEN_TOP = 44;
    private final ClientHistoryStore history;
    private final ClientPendingStatisticsStore pendingStatistics;
    private final ClientNotificationStore notifications;
    private final Supplier<HighlightPreparationProgress> highlightProgress;
    private final LumiHudPanel panel = new LumiHudPanel();
    private ClientLevel world;

    public LumiOperationHud(
            ClientHistoryStore history,
            ClientPendingStatisticsStore pendingStatistics,
            ClientNotificationStore notifications,
            Supplier<HighlightPreparationProgress> highlightProgress) {
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.pendingStatistics = java.util.Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
        this.notifications = java.util.Objects.requireNonNull(
                notifications, "notifications");
        this.highlightProgress = java.util.Objects.requireNonNull(
                highlightProgress, "highlightProgress");
    }

    public void register() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR, ID, this::render);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof LumiScreen) {
                ScreenEvents.afterRender(screen).register(
                        (current, graphics, mouseX, mouseY, tickDelta) -> {
                            if (client.screen == current) {
                                renderScreenOverlay(graphics);
                            }
                        });
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    notifications.clear();
                    panel.clear();
                    world = null;
                });
    }

    private void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        if (!usesGui() || Minecraft.getInstance().screen instanceof LumiScreen) return;
        renderPanel(graphics, 10, true);
    }

    private void renderScreenOverlay(GuiGraphics graphics) {
        if (!usesGui()) return;
        renderPanel(graphics, SCREEN_TOP, false);
    }

    private void renderPanel(GuiGraphics graphics, int y, boolean inWorld) {
        var client = Minecraft.getInstance();
        if (client.options.hideGui || client.player == null || client.level == null) return;
        if (world != client.level) {
            world = client.level;
            panel.clear();
        }
        var state = history.state();
        var snapshot = state.snapshot().orElse(null);
        var feedback = notifications.visible();
        var error = feedback.stream().filter(ClientNotificationStore.Notification::error)
                .reduce((first, last) -> last).orElse(null);
        var operation = state.activeOperation()
                .filter(event -> !history.isNativeWorldAction(event.requestId())).orElse(null);
        var local = highlightProgress.get();
        var shortcuts = inWorld ? shortcuts(client) : List.<String>of();
        LumiHudPanel.Content content;
        if (error != null) content = feedback(error, shortcuts);
        else if (operation != null) content = operation(operation, shortcuts);
        else if (local.preparing()) content = new LumiHudPanel.Content("highlight",
                translated("luma.status.highlight_preparing", local.percent()),
                snapshot == null ? "" : snapshot.workspaceName(), false, false, false,
                true, OptionalDouble.of(local.fraction()), shortcuts, LumiTheme.accent());
        else if (!feedback.isEmpty()) content = feedback(feedback.getLast(), shortcuts);
        else if (inWorld && snapshot != null) content = workspace(snapshot, shortcuts);
        else {
            panel.clear();
            return;
        }
        panel.render(graphics, content, y);
    }

    LumiHudPanel.Content operation(OperationEventPayload event, List<String> shortcuts) {
        var request = history.request(event.requestId()).orElse(null);
        String title = translated("lumi.hud.working");
        if (request != null) {
            title = request.undo() ? translated("lumi.hud.undoing")
                    : request.redo() ? translated("lumi.hud.redoing") : switch (request.kind()) {
                        case SAVE, AMEND, ZONE_SAVE, ZONE_AMEND -> translated("lumi.hud.saving");
                        case BRANCH_SWITCH -> translated("lumi.hud.switching");
                        case RESTORE, RESTORE_NO_ENTITIES, RESTORE_AREA, RESTORE_AREA_APPLY,
                                ZONE_RESTORE, QUICK_ROLLBACK, RECOVER_RESUME, RECOVER_RETURN -> translated("lumi.hud.restoring");
                        default -> title;
                    };
        }
        String detail = event.queuePosition() > 0 ? translated(event.message())
                : event.progress().map(value -> translated(value.phase())).orElseGet(() -> translated(event.message()));
        return new LumiHudPanel.Content(event.requestId(), title, detail, false, false, false,
                true, event.progress().map(value -> value.fraction()).orElse(OptionalDouble.empty()),
                shortcuts, LumiTheme.accent());
    }

    LumiHudPanel.Content feedback(ClientNotificationStore.Notification notification, List<String> shortcuts) {
        var request = notification.request().orElse(null);
        String title = notification.error() ? translated("lumi.hud.blocked") : "Lumi";
        String detail = notification.message().getString();
        boolean saved = false;
        Object identity = notification.expiresAtNanos();
        if (request == null && !notification.error()) {
            var contents = notification.message().getContents();
            identity = contents instanceof TranslatableContents translation
                    ? translation.getKey() : notification.message();
        }
        if (!notification.error() && request != null) {
            if (request.undo() || request.redo()) {
                title = translated(request.undo() ? "lumi.hud.undone" : "lumi.hud.redone");
                detail = history.state().snapshot().map(snapshot -> snapshot.workspaceName()
                        + " · " + shortBranch(snapshot.branchName())).orElse(detail);
                if (notification.repetitions() > 1) title += " × " + notification.repetitions();
                identity = request.kind();
            } else switch (request.kind()) {
                case SAVE, AMEND, ZONE_SAVE, ZONE_AMEND -> {
                    saved = true;
                    title = translated("lumi.hud.saved");
                    detail = request.detail();
                }
                case BRANCH_SWITCH, BRANCH_CREATE -> {
                    title = shortBranch(request.detail());
                    detail = translated(request.kind() == io.github.lumi.network.HistoryCommandPayload.Kind.BRANCH_CREATE
                            ? "luma.status.variant_created" : "luma.status.variant_switched");
                }
                default -> { }
            }
        }
        return new LumiHudPanel.Content(identity, title, detail, false, saved, notification.error(),
                false, OptionalDouble.empty(), shortcuts, request == null ? notification.argb() : LumiTheme.accent());
    }

    private LumiHudPanel.Content workspace(HistorySnapshotPayload snapshot, List<String> shortcuts) {
        String pending = snapshot.pendingKeys() == 0 ? translated("lumi.hud.all_saved")
                : pendingStatistics.result(snapshot).filter(result -> result.error().isEmpty())
                        .map(result -> translated("lumi.hud.pending_changes", result.workspace().total()))
                        .orElseGet(() -> translated("lumi.hud.pending_areas", Integer.toString(snapshot.pendingKeys())));
        return new LumiHudPanel.Content(snapshot.workspaceId(),
                snapshot.workspaceName() + " · " + shortBranch(snapshot.branchName()), pending,
                true, false, false, false, OptionalDouble.empty(), shortcuts, LumiTheme.accent());
    }

    private static List<String> shortcuts(Minecraft client) {
        if (!LumiHotkeys.actionModifierDown(client.options.keyMappings)) return List.of();
        String action = binding(client, "key.lumi.action_modifier");
        return List.of(
                action + "+" + binding(client, "key.lumi.quick_save") + " save · " + action + "+"
                        + binding(client, "key.lumi.undo") + "/" + binding(client, "key.lumi.redo") + " undo/redo",
                action + "+" + binding(client, "key.lumi.open_dashboard") + " history · " + action + "+"
                        + binding(client, "key.lumi.quick_rollback") + " rollback",
                action + "+1..0 switch branch");
    }

    private static String shortBranch(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static String translated(String value, Object... arguments) {
        return value.startsWith("luma.") || value.startsWith("lumi.")
                ? Component.translatable(value, arguments).getString() : value;
    }

    private boolean usesGui() {
        return history.state().hudDisplayMode() == HudDisplayMode.GUI;
    }

    static int indeterminateOffset(long millis, int travelWidth) {
        long position = Math.floorMod(millis / 20, travelWidth * 2L);
        return (int) (position <= travelWidth
                ? position : travelWidth * 2L - position);
    }

    private static String binding(Minecraft client, String name) {
        return LumiHotkeys.bindingLabel(client.options.keyMappings, name);
    }
}
