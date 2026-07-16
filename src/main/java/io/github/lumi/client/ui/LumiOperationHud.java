package io.github.lumi.client.ui;

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
        int y = 10;
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
}
