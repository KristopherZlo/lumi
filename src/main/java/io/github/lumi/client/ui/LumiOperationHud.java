package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.LumiMod;
import io.github.lumi.client.state.ClientHistoryStore;
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

    public LumiOperationHud(ClientHistoryStore history) {
        this.history = java.util.Objects.requireNonNull(history, "history");
    }

    public void register() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR, ID, this::render);
    }

    private void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker ignored) {
        int y = renderWorkspace(graphics);
        OperationEventPayload event = history.state().activeOperation().orElse(null);
        if (event == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        String text = event.queuePosition() > 0
                ? event.message() : event.progress().map(value -> value.phase())
                        .orElse(event.message());
        int width = Math.min(240, Math.max(120, font.width(text) + 20));
        int x = graphics.guiWidth() - width - 10;
        graphics.fill(x, y, x + width, y + 28, 0xd9111419);
        graphics.drawString(font, font.plainSubstrByWidth(text, width - 16),
                x + 8, y + 6, 0xfff0f3f6, false);
        event.progress().flatMap(progress -> progress.fraction().isPresent()
                ? java.util.Optional.of(progress.fraction().orElseThrow())
                : java.util.Optional.empty()).ifPresent(fraction -> {
                    int barWidth = width - 16;
                    graphics.fill(x + 8, y + 20, x + 8 + barWidth, y + 23, 0xff343a43);
                    graphics.fill(x + 8, y + 20,
                            x + 8 + (int) Math.round(barWidth * fraction), y + 23,
                            0xff70d6a5);
                });
    }

    private int renderWorkspace(GuiGraphics graphics) {
        var snapshot = history.state().snapshot().orElse(null);
        var client = Minecraft.getInstance();
        if (snapshot == null || client.player == null) {
            return 10;
        }
        boolean enabled = snapshot.workspaces().stream()
                .filter(io.github.lumi.network.HistorySnapshotPayload.WorkspaceView::active)
                .findFirst()
                .map(io.github.lumi.network.HistorySnapshotPayload.WorkspaceView::workspaceHudEnabled)
                .orElse(true);
        if (!enabled) {
            return 10;
        }
        boolean expanded = InputConstants.isKeyDown(
                client.getWindow(), InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RALT);
        String branch = snapshot.branchName();
        int slash = branch.lastIndexOf('/');
        if (slash >= 0) {
            branch = branch.substring(slash + 1);
        }
        String title = snapshot.workspaceName() + " · " + branch
                + " · " + snapshot.pendingKeys() + " pending";
        int width = Math.min(270, Math.max(150, client.font.width(title) + 16));
        int height = expanded ? 68 : 22;
        int x = graphics.guiWidth() - width - 10;
        graphics.fill(x, 10, x + width, 10 + height, 0xd9111419);
        graphics.drawString(client.font, client.font.plainSubstrByWidth(title, width - 12),
                x + 6, 17, snapshot.pendingKeys() == 0 ? 0xffaeb6c2 : 0xffffd166, false);
        if (expanded) {
            graphics.drawString(client.font, "Alt+S save · Alt+Z/Y undo/redo",
                    x + 6, 35, 0xfff0f3f6, false);
            graphics.drawString(client.font, "U history · Alt+R rollback",
                    x + 6, 48, 0xfff0f3f6, false);
            graphics.drawString(client.font, "Alt+1..0 switch branch",
                    x + 6, 61, 0xff8f9aa8, false);
        }
        return nextPanelY(10, height);
    }

    static int nextPanelY(int top, int height) {
        return top + height + 6;
    }
}
