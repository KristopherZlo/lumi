package io.github.lumi.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared window chrome for V2 modal workflows. */
abstract class LumiModalScreen extends LumiScreen {
    private final Screen background;

    protected LumiModalScreen(Component title) {
        this(Minecraft.getInstance().screen, title);
    }

    protected LumiModalScreen(Screen background, Component title) {
        super(title);
        this.background = background;
    }

    @Override
    protected void renderUnderlay(GuiGraphics graphics) {
        if (background != null && background != this
                && (!(background instanceof LumiScreen screen)
                        || screen.screenInitialized())) {
            background.render(graphics, -1, -1, 0.0F);
        }
    }

}
