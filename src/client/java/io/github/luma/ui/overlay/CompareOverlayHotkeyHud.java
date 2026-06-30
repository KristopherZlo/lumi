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
    private static final int CARD_WIDTH = 150;
    private static final int CARD_HEIGHT = 29;
    private static final int MARGIN = 6;
    private static final int ITEM_GAP = 10;

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
        Minecraft client = Minecraft.getInstance();
        return reservedBottomHeightForState(
                client != null && client.options != null && client.options.hideGui,
                CompareOverlayRenderer.hasData(),
                CompareOverlayRenderer.visible(),
                CompareOverlayRenderer.changedBlockCount()
        );
    }

    static int reservedBottomHeightForState(boolean hudHidden, boolean hasData, boolean visible, int changedBlockCount) {
        return shouldRenderForState(hudHidden, hasData, visible, changedBlockCount) ? CARD_HEIGHT + MARGIN : 0;
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
        int cursor = x + 7;
        int rowY = y + ((CARD_HEIGHT - RoundedHudRenderer.COMPACT_KEY_HEIGHT) / 2);
        cursor += shortcutItem(
                graphics,
                font,
                cursor,
                rowY,
                LumiClientKeyBindings.key(LumiClientKeyBindings.Role.COMPARE),
                "H",
                "Toggle"
        ) + ITEM_GAP;
        shortcutItem(
                graphics,
                font,
                cursor,
                rowY,
                LumiClientKeyBindings.key(LumiClientKeyBindings.Role.ACTION),
                "ACTION",
                "X-Ray"
        );
    }

    private static boolean shouldRender(Minecraft client) {
        return client != null
                && client.options != null
                && shouldRenderForState(
                        client.options.hideGui,
                        CompareOverlayRenderer.hasData(),
                        CompareOverlayRenderer.visible(),
                        CompareOverlayRenderer.changedBlockCount()
                );
    }

    static boolean shouldRenderForState(boolean hudHidden, boolean hasData, boolean visible, int changedBlockCount) {
        return !hudHidden && hasData && visible && changedBlockCount > 0;
    }

    private static int shortcutItem(
            GuiGraphics graphics,
            Font font,
            int x,
            int y,
            KeyMapping key,
            String fallback,
            String action
    ) {
        int keyWidth = RoundedHudRenderer.key(graphics, key, x, y, fallback, true, key != null && key.isDown());
        int textX = x + keyWidth + 3;
        String label = action == null ? "" : action;
        graphics.drawString(font, Component.literal(label), textX, y + 3, RoundedHudRenderer.MUTED, false);
        return keyWidth + 3 + font.width(label);
    }
}
