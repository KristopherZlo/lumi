package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Flat Lumi control used instead of Minecraft's textured button. */
public final class LumiButton extends Button {
    private static final int TEXT = 0xfff4f1ea;
    private static final int TEXT_DISABLED = 0xff77736d;
    private static final int CONTROL_HEIGHT = 18;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int ICON_TEXTURE_SIZE = 24;
    private final Kind kind;
    private final Identifier icon;
    private final Identifier disabledIcon;
    private final Integer accentColor;

    public LumiButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind) {
        this(x, y, width, height, message, onPress, kind, null);
    }

    public LumiButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind, String iconName) {
        this(x, y, width, height, message, onPress, kind, iconName, null);
    }

    public LumiButton(
            int x, int y, int width, int height,
            Component message, OnPress onPress, Kind kind,
            String iconName, Integer accentColor) {
        super(
                x, y, width,
                CONTROL_HEIGHT,
                message, onPress, DEFAULT_NARRATION);
        this.kind = kind;
        this.accentColor = accentColor;
        boolean sliders = "sliders".equals(iconName);
        boolean support = "buymeacoffee".equals(iconName)
                || "paypal".equals(iconName);
        this.icon = iconName == null ? null : Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, support
                        ? "textures/gui/" + iconName + ".png"
                        : sliders ? "textures/gui/new-icons/sliders.png"
                        : "textures/gui/icons/" + iconName + ".png");
        this.disabledIcon = sliders || support ? icon : iconName == null ? null
                : Identifier.fromNamespaceAndPath(
                        LumiMod.MOD_ID,
                        "textures/gui/icons/" + iconName + "_disabled.png");
        if (icon != null) {
            setTooltip(Tooltip.create(message));
        }
    }

    static int contentWidth(int maximum, Component label) {
        return fittedWidth(maximum, Minecraft.getInstance().font.width(label));
    }

    static int fittedWidth(int maximum, int textWidth) {
        return Math.min(Math.max(0, maximum),
                Math.max(CONTROL_HEIGHT, Math.max(0, textWidth) + 12));
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
            int iconX = getWidth() > ICON_BUTTON_WIDTH
                    ? getX() + 4 : getX() + (getWidth() - size) / 2;
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, active ? icon : disabledIcon,
                    iconX,
                    getY() + (getHeight() - size) / 2,
                    0, 0, size, size,
                    ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                    ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
            if (getWidth() <= ICON_BUTTON_WIDTH) return;
        }
        var font = Minecraft.getInstance().font;
        int textX = getX() + (icon == null ? 4 : 20);
        int available = Math.max(0, getX() + getWidth() - 4 - textX);
        String label = getMessage().getString();
        if (font.width(label) > available) {
            String suffix = available >= font.width("…") ? "…" : "";
            label = font.plainSubstrByWidth(
                    label, Math.max(0, available - font.width(suffix))) + suffix;
            setTooltip(Tooltip.create(getMessage()));
        }
        graphics.enableScissor(
                textX, getY() + 1,
                getX() + getWidth() - 2, getY() + getHeight() - 1);
        graphics.drawCenteredString(
                font, label,
                textX + available / 2,
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
