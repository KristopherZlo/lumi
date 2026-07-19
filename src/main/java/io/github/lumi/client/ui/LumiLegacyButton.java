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
    private static final int CONTROL_HEIGHT = 18;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int ICON_TEXTURE_SIZE = 24;
    private final Kind kind;
    private final Identifier icon;
    private final Identifier disabledIcon;
    private final Integer accentColor;

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind) {
        this(x, y, width, height, message, onPress, kind, null);
    }

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind, String iconName) {
        this(x, y, width, height, message, onPress, kind, iconName, null);
    }

    public LumiLegacyButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind,
            String iconName, Integer accentColor) {
        super(
                x, y, iconName == null ? contentWidth(width, message) : ICON_BUTTON_WIDTH,
                CONTROL_HEIGHT,
                message, onPress, DEFAULT_NARRATION);
        this.kind = kind;
        this.accentColor = accentColor;
        boolean sliders = "sliders".equals(iconName);
        this.icon = iconName == null ? null : Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, sliders
                        ? "textures/gui/new-icons/sliders.png"
                        : "textures/gui/icons/" + iconName + ".png");
        this.disabledIcon = sliders ? icon : iconName == null ? null
                : Identifier.fromNamespaceAndPath(
                        LumiMod.MOD_ID,
                        "textures/gui/icons/" + iconName + "_disabled.png");
        if (icon != null) {
            setTooltip(Tooltip.create(message));
        }
    }

    private static int contentWidth(int maximum, Component label) {
        return Math.min(maximum, Math.max(CONTROL_HEIGHT,
                Minecraft.getInstance().font.width(label) + 12));
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int fill = active ? kind.fill(isHoveredOrFocused()) : 0xff18191b;
        if (active && accentColor != null) {
            graphics.fill(
                    getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    accentColor);
            fill = (isHoveredOrFocused() ? 0xbb000000 : 0x88000000)
                    | (accentColor & 0x00ffffff);
            graphics.fill(
                    getX() + 1, getY() + 1,
                    getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
        } else {
            graphics.fill(
                    getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
        }
        if (icon != null) {
            int preferredSize = LumiUiScale.current().targetGuiScale() == 3 ? 8 : 12;
            int size = Math.min(preferredSize, Math.min(getWidth(), getHeight()));
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, active ? icon : disabledIcon,
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
        NORMAL(0xff2a292c, 0xff39363a),
        PRIMARY(0xff7a5a21, 0xff936d29),
        DANGER(0xff7a2424, 0xff963030),
        SELECTED(0xff211f18, 0xff211f18);

        private final int fill;
        private final int hover;

        Kind(int fill, int hover) {
            this.fill = fill;
            this.hover = hover;
        }

        int fill(boolean hovered) {
            return hovered ? hover : fill;
        }
    }
}
