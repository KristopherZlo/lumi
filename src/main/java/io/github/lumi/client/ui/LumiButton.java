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
public class LumiButton extends Button {
    private static final int TEXT = 0xfff4f1ea;
    private static final int TEXT_DISABLED = 0xff77736d;
    private static final int CONTROL_HEIGHT = 18;
    private static final int ICON_BUTTON_WIDTH = 26;
    private static final int ICON_TEXTURE_SIZE = 24;
    private final Kind kind;
    private final Identifier icon;
    private final Identifier disabledIcon;
    private final Integer accentColor;
    private final LumiMotion hoverMotion = new LumiMotion();
    private boolean upsideDown;

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
        boolean leftIconed = "buymeacoffee".equals(iconName)
                || "paypal".equals(iconName)
                || "bug".equals(iconName);
        this.icon = iconName == null ? null : Identifier.fromNamespaceAndPath(
                LumiMod.MOD_ID, leftIconed
                        ? "textures/gui/left-iconed-buttons/" + iconName + ".png"
                        : "textures/gui/icons/" + iconName + ".png");
        this.disabledIcon = leftIconed || "sliders".equals(iconName)
                ? icon : iconName == null ? null
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

    void rotateContents180() {
        upsideDown = true;
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!upsideDown) {
            renderFlatContents(graphics);
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().rotateAbout(
                (float) Math.PI,
                getX() + getWidth() / 2.0F,
                getY() + getHeight() / 2.0F);
        try {
            renderFlatContents(graphics);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void renderFlatContents(GuiGraphics graphics) {
        float hover = hoverMotion.toward(active && isHoveredOrFocused() ? 1 : 0);
        int fill = active ? kind.fill(false) : 0xff18191b;
        if (active && accentColor != null) {
            graphics.fill(
                    getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    accentColor);
            fill = LumiTheme.withAlpha(
                    accentColor, Math.round(0x88 + (0xbb - 0x88) * hover));
            graphics.fill(
                    getX() + 1, getY() + 1,
                    getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
        } else {
            graphics.fill(
                    getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            if (active && hover > 0) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                        LumiTheme.withOpacity(kind.fill(true), hover));
            }
        }
        if (active && kind == Kind.SELECTED) {
            graphics.fill(getX(), getY() + 3, getX() + 2, getY() + getHeight() - 3,
                    accentColor == null ? LumiTheme.accent() : accentColor);
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
        int available = labelAvailable(
                Math.max(0, getX() + getWidth() - 4 - textX));
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
        int color = active ? TEXT : TEXT_DISABLED;
        if (leftAlignedLabel()) {
            graphics.drawString(
                    font, label, textX + 1,
                    getY() + (getHeight() - 8) / 2, color, false);
        } else {
            graphics.drawCenteredString(
                    font, label, textX + available / 2,
                    getY() + (getHeight() - 8) / 2, color);
        }
        graphics.disableScissor();
    }

    protected int labelAvailable(int available) {
        return available;
    }

    protected boolean leftAlignedLabel() {
        return false;
    }

    public enum Kind {
        NORMAL, PRIMARY, DANGER, SELECTED;

        int fill(boolean hovered) {
            return switch (this) {
                case NORMAL -> hovered ? 0xff343438 : 0xff222326;
                case PRIMARY -> LumiTheme.accentSurface(hovered ? .52F : .42F);
                case DANGER -> hovered ? 0xff783438 : 0xff40262a;
                case SELECTED -> LumiTheme.accentSurface(.08F);
            };
        }
    }
}
