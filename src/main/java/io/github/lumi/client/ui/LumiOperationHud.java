package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.domain.model.HudDisplayMode;
import io.github.lumi.network.OperationEventPayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/** Compact operation/queue HUD backed only by immutable server events. */
public final class LumiOperationHud {
    private static final Identifier ID =
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "operation_hud");
    private final ClientHistoryStore history;
    private final ClientPendingStatisticsStore pendingStatistics;

    public LumiOperationHud(
            ClientHistoryStore history,
            ClientPendingStatisticsStore pendingStatistics) {
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.pendingStatistics = java.util.Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
    }

    public void register() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR, ID, this::render);
    }

    private void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        int y = renderWorkspace(graphics);
        OperationEventPayload event = history.state().activeOperation().orElse(null);
        if (event == null) return;
        var font = Minecraft.getInstance().font;
        String text = event.queuePosition() > 0
                ? event.message() : event.progress().map(value -> value.phase())
                        .orElse(event.message());
        int width = Math.min(Math.max(1, graphics.guiWidth() - 20),
                Math.min(240, Math.max(120, font.width(text) + 20)));
        int x = graphics.guiWidth() - width - 10;
        graphics.fill(x, y, x + width, y + 28, 0xd9111419);
        drawClipped(graphics, text, x + 8, y + 6, width - 16, 0xfff0f3f6);
        event.progress().ifPresent(progress -> {
            int barWidth = width - 16;
            graphics.fill(x + 8, y + 20, x + 8 + barWidth, y + 23, 0xff343a43);
            if (progress.fraction().isPresent()) {
                double fraction = progress.fraction().orElseThrow();
                graphics.fill(x + 8, y + 20,
                        x + 8 + (int) Math.round(barWidth * fraction), y + 23,
                        0xff70d6a5);
                return;
            }
            int segmentWidth = Math.max(12, barWidth / 4);
            int offset = indeterminateOffset(
                    System.currentTimeMillis(), barWidth - segmentWidth);
            graphics.fill(x + 8 + offset, y + 20,
                    x + 8 + offset + segmentWidth, y + 23, 0xff70d6a5);
        });
    }

    private int renderWorkspace(GuiGraphics graphics) {
        var snapshot = history.state().snapshot().orElse(null);
        var client = Minecraft.getInstance();
        if (snapshot == null || client.player == null) return 10;
        boolean enabled = snapshot.workspaces().stream()
                .filter(io.github.lumi.network.HistorySnapshotPayload.WorkspaceView::active)
                .findFirst()
                .map(workspace -> workspace.hudDisplayMode() == HudDisplayMode.GUI)
                .orElse(true);
        if (!enabled) return 10;
        boolean expanded = LumiHotkeys.actionModifierDown(
                client.options.keyMappings);
        String branch = snapshot.branchName();
        int slash = branch.lastIndexOf('/');
        if (slash >= 0) branch = branch.substring(slash + 1);
        String pending = pendingStatistics.result(snapshot)
                .filter(result -> result.error().isEmpty())
                .map(result -> Long.toString(result.workspace().total()))
                .orElseGet(() -> Integer.toString(snapshot.pendingKeys()));
        String title = snapshot.workspaceName() + " · " + branch
                + " · " + pending + " pending";
        int height = expanded ? 68 : 22;
        int width = Math.min(Math.max(1, graphics.guiWidth() - 20),
                expanded ? 270
                        : Math.min(270,
                                Math.max(150, client.font.width(title) + 16)));
        int x = graphics.guiWidth() - width - 10;
        graphics.fill(x, 10, x + width, 10 + height, 0xd9111419);
        drawClipped(graphics, title, x + 6, 17, width - 12,
                snapshot.pendingKeys() == 0 ? 0xffaeb6c2 : 0xffffd166);
        if (expanded) {
            String action = binding(client, "key.lumi.action_modifier");
            drawClipped(graphics,
                    action + "+" + binding(client, "key.lumi.quick_save")
                            + " save · " + action + "+"
                            + binding(client, "key.lumi.undo") + "/"
                            + binding(client, "key.lumi.redo") + " undo/redo",
                    x + 6, 35, width - 12, 0xfff0f3f6);
            drawClipped(graphics,
                    action + "+" + binding(client, "key.lumi.open_dashboard")
                            + " history · "
                            + binding(client, "key.lumi.quick_rollback")
                            + " rollback",
                    x + 6, 48, width - 12, 0xfff0f3f6);
            drawClipped(graphics,
                    action + "+1..0 switch branch",
                    x + 6, 61, width - 12, 0xff8f9aa8);
        }
        return nextPanelY(10, height);
    }

    static int nextPanelY(int top, int height) {
        return top + height + 6;
    }

    static int indeterminateOffset(long millis, int travelWidth) {
        long position = Math.floorMod(millis / 20, travelWidth * 2L);
        return (int) (position <= travelWidth
                ? position : travelWidth * 2L - position);
    }

    private static void drawClipped(
            GuiGraphics graphics, String text, int x, int y,
            int width, int color) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font,
                font.plainSubstrByWidth(text, Math.max(0, width)),
                x, y, color, false);
    }

    private static String binding(Minecraft client, String name) {
        return LumiHotkeys.bindingLabel(client.options.keyMappings, name);
    }
}
