package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Flat legacy Lumi control used instead of Minecraft's textured button. */
public final class LumiLegacyButton extends Button {
    private static final int TEXT = 0xfff4f1ea;
    private static final int TEXT_DISABLED = 0xff77736d;
    private static final int ICON_TEXTURE_SIZE = 24;
    private final Kind kind;
    private final Identifier icon;

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind) {
        this(x, y, width, height, message, onPress, kind, null);
    }

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind, String iconName) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.kind = kind;
        this.icon = iconName == null ? null : Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, "textures/gui/new-icons/" + iconName + ".png");
        if (icon != null) {
            setTooltip(Tooltip.create(message));
        }
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
        if (icon != null) {
            int size = Math.min(12, Math.min(getWidth() - 4, getHeight() - 4));
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, icon,
                    getX() + (getWidth() - size) / 2,
                    getY() + (getHeight() - size) / 2,
                    0, 0, size, size,
                    ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                    ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
            return;
        }
        var font = Minecraft.getInstance().font;
        int available = Math.max(0, getWidth() - 8);
        String label = getMessage().getString();
        if (font.width(label) > available) {
            String suffix = available >= font.width("…") ? "…" : "";
            label = font.plainSubstrByWidth(
                    label, Math.max(0, available - font.width(suffix))) + suffix;
            setTooltip(Tooltip.create(getMessage()));
        }
        graphics.enableScissor(
                getX() + 2, getY() + 1,
                getX() + getWidth() - 2, getY() + getHeight() - 1);
        graphics.drawCenteredString(
                font, label,
                getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                active ? TEXT : TEXT_DISABLED);
        graphics.disableScissor();
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
