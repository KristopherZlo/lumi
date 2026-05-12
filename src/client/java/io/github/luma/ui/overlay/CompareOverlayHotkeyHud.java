package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.client.input.LumiClientKeyBindings;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CompareOverlayHotkeyHud {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "compare_overlay_hotkeys"
    );
    private static final int CARD_WIDTH = 164;
    private static final int CARD_HEIGHT = 52;
    private static final int MARGIN = 8;
    private static final int ROW_GAP = 16;

    private CompareOverlayHotkeyHud() {
    }

    public static void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE,
                HUD_ELEMENT_ID,
                CompareOverlayHotkeyHud::render
        );
    }

    public static int reservedBottomHeight() {
        return shouldRender(Minecraft.getInstance()) ? CARD_HEIGHT + MARGIN : 0;
    }

    private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (!shouldRender(client)) {
            return;
        }
        Font font = client.font;
        int x = MARGIN;
        int y = Math.max(MARGIN, graphics.guiHeight() - CARD_HEIGHT - MARGIN);

        RoundedHudRenderer.card(graphics, x, y, CARD_WIDTH, CARD_HEIGHT);
        graphics.drawString(font, Component.literal("Compare Overlay"), x + 8, y + 6, RoundedHudRenderer.TEXT, false);

        int rowY = y + 20;
        shortcutRow(graphics, font, x + 8, rowY, null, LumiClientKeyBindings.key(LumiClientKeyBindings.Role.COMPARE), "H", "Show/Hide");
        shortcutRow(graphics, font, x + 8, rowY + ROW_GAP, "Hold", LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION), "Alt", "X-Ray");
    }

    private static boolean shouldRender(Minecraft client) {
        return client != null
                && client.options != null
                && !client.options.hideGui
                && CompareOverlayRenderer.hasData()
                && CompareOverlayRenderer.changedBlockCount() > 0;
    }

    private static void shortcutRow(
            GuiGraphics graphics,
            Font font,
            int x,
            int y,
            String prefix,
            KeyMapping key,
            String fallback,
            String action
    ) {
        int cursor = x;
        if (prefix != null && !prefix.isBlank()) {
            cursor += RoundedHudRenderer.textChip(graphics, prefix, cursor, y, true) + 2;
        }
        cursor += RoundedHudRenderer.key(graphics, key, cursor, y, fallback, true);
        int textX = cursor + 4;
        graphics.drawString(font, Component.literal(": " + action), textX, y + 3, RoundedHudRenderer.MUTED, false);
    }
}
