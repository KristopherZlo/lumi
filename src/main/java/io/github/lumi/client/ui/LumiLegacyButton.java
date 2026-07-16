package io.github.lumi.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat legacy Lumi control used instead of Minecraft's textured button. */
public final class LumiLegacyButton extends Button {
    private static final int TEXT = 0xfff4f1ea;
    private static final int TEXT_DISABLED = 0xff77736d;
    private final Kind kind;

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.kind = kind;
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int fill = active ? kind.fill(isHoveredOrFocused()) : 0xff18191b;
        int border = kind.border();
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
        graphics.fill(
                getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                fill);
        graphics.drawCenteredString(
                Minecraft.getInstance().font,
                getMessage(),
                getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                active ? TEXT : TEXT_DISABLED);
    }

    public enum Kind {
        NORMAL(0xff2a292c, 0xff39363a, 0xff45413a),
        PRIMARY(0xff7a5a21, 0xff936d29, 0xffd9b86c),
        DANGER(0xff7a2424, 0xff963030, 0xffff8585),
        SELECTED(0xff24211b, 0xff302a20, 0xffe0b95a);

        private final int fill;
        private final int hover;
        private final int border;

        Kind(int fill, int hover, int border) {
            this.fill = fill;
            this.hover = hover;
            this.border = border;
        }

        int fill(boolean hovered) {
            return hovered ? hover : fill;
        }

        int border() {
            return border;
        }
    }
}
