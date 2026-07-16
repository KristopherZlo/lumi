package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared legacy window chrome for V2 modal workflows. */
abstract class LumiLegacyModalScreen extends Screen {
    protected LumiLegacyModalScreen(Component title) {
        super(title);
    }

    protected final LumiLegacyButton addLegacyButton(
            int x, int y, int width, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        return addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final void renderLegacyWindow(
            GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(0, 0, this.width, this.height, LegacyLumiTheme.BACKDROP);
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.WINDOW_BORDER);
    }

    protected final void renderLegacyPanel(
            GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(
                graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
    }

    protected static Component errorText(String error) {
        return error.startsWith("luma.")
                ? Component.translatable(error) : Component.literal(error);
    }
}
