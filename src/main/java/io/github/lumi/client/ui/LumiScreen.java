package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Neutral V2 screen mechanics shared by pages and modal workflows. */
abstract class LumiScreen extends Screen {
    protected static final int INPUT_HEIGHT = 14;
    protected static final int INPUT_FRAME_HEIGHT = 18;

    protected LumiScreen(Component title) {
        super(title);
    }

    protected final LumiButton addButton(
            int x, int y, int width, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addRenderableWidget(new LumiButton(
                x, y, width, 20, label, ignored -> action.run(), kind));
    }

    protected final LumiButton addContentButton(
            int x, int y, int maximumWidth, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addButton(
                x, y, LumiButton.contentWidth(maximumWidth, label),
                label, action, kind);
    }

    protected final LumiButton addIconButton(
            int x, int y, String icon, Component label,
            Runnable action, LumiButton.Kind kind) {
        return addRenderableWidget(new LumiButton(
                x, y, 26, 20, label, ignored -> action.run(), kind, icon));
    }

    protected final LumiTextField addTextField(
            int x, int y, int width, Component label) {
        return addRenderableWidget(new LumiTextField(font, x, y, width, label));
    }

    protected final void renderTextField(
            GuiGraphics graphics, EditBox field) {
        if (!(field instanceof LumiTextField)) {
            LumiTheme.outlined(
                    graphics, field.getX() - 6, field.getY(),
                    field.getWidth() + 12, INPUT_FRAME_HEIGHT,
                    LumiTheme.INSET, LumiTheme.INSET_BORDER);
        }
    }
}
