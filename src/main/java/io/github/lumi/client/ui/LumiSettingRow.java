package io.github.lumi.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** One descriptive setting with a compact state control. */
final class LumiSettingRow extends Button {
    static final int HEIGHT = 32;
    private static final int CONTROL_HEIGHT = 18;
    private static final int MIN_VALUE_WIDTH = 42;
    private final Component label;
    private final Component description;
    private final Component value;
    private final int reservedControlWidth;
    private final boolean selected;
    private final boolean destructive;
    private final LumiMotion toggleMotion;
    private final LumiMotion hoverMotion = new LumiMotion();

    static LumiSettingRow toggle(
            int x, int y, int width,
            Component label, Component description, boolean selected,
            LumiMotion motion, OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.translatable(selected ? "options.on" : "options.off"),
                0, selected, false, motion, onPress);
    }

    static LumiSettingRow action(
            int x, int y, int width,
            Component label, Component description, OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.empty(), 0, false, true, null, onPress);
    }

    static LumiSettingRow value(int x, int y, int width, Component label,
            Component description, Component value, OnPress onPress) {
        return new LumiSettingRow(x, y, width, label, description, value, 0, false, false, null, onPress);
    }

    static LumiSettingRow choice(
            int x, int y, int width,
            Component label, Component description,
            int reservedControlWidth, OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.empty(), Math.max(0, reservedControlWidth),
                false, false, null, onPress);
    }

    private LumiSettingRow(
            int x, int y, int width,
            Component label, Component description, Component value,
            int reservedControlWidth,
            boolean selected, boolean destructive, LumiMotion toggleMotion, OnPress onPress) {
        super(x, y, width, HEIGHT,
                value.getString().isBlank() ? label : label.copy().append(": ").append(value),
                onPress, DEFAULT_NARRATION);
        this.label = label;
        this.description = description;
        this.value = value;
        this.reservedControlWidth = reservedControlWidth;
        this.selected = selected;
        this.destructive = destructive;
        this.toggleMotion = toggleMotion;
        if (!description.getString().isBlank()) {
            setTooltip(Tooltip.create(description));
        }
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float hover = hoverMotion.toward(active && isHoveredOrFocused() ? 1 : 0);
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                LumiTheme.withOpacity(LumiTheme.INSET, .35F));
        if (hover > 0) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                    LumiTheme.withOpacity(LumiTheme.CHIP, hover * .7F));
        }
        graphics.fill(getX() + 8, getY() + getHeight() - 1,
                getX() + getWidth() - 8, getY() + getHeight(), LumiTheme.INSET_BORDER);
        if (active && isFocused()) {
            graphics.fill(getX(), getY() + 4, getX() + 2, getY() + getHeight() - 4,
                    LumiTheme.ACCENT);
        }

        var font = Minecraft.getInstance().font;
        String valueText = value.getString();
        int valueWidth = toggleMotion != null ? toggleWidth(getWidth())
                : valueText.isEmpty() ? reservedControlWidth : valueWidth(getWidth(), font.width(value));
        int textWidth = Math.max(0, getWidth() - valueWidth - 22);
        int textColor = active
                ? destructive ? LumiTheme.DANGER : LumiTheme.TEXT
                : LumiTheme.MUTED;
        int titleY = description.getString().isBlank()
                ? getY() + (getHeight() - 8) / 2 : getY() + 5;
        graphics.drawString(font,
                font.plainSubstrByWidth(label.getString(), textWidth),
                getX() + 8, titleY, textColor, false);
        if (!description.getString().isBlank()) {
            graphics.drawString(font,
                    font.plainSubstrByWidth(description.getString(), textWidth),
                    getX() + 8, getY() + 18,
                    LumiTheme.MUTED, false);
        }
        if (valueWidth == 0 || valueText.isEmpty()) {
            return;
        }

        if (toggleMotion != null) {
            drawToggle(graphics, valueWidth);
            return;
        }

        int valueX = getX() + getWidth() - valueWidth - 6;
        int valueY = getY() + (getHeight() - CONTROL_HEIGHT) / 2;
        LumiTheme.outlined(
                graphics, valueX, valueY, valueWidth, CONTROL_HEIGHT,
                selected ? LumiTheme.STATUS : LumiTheme.CHIP,
                selected
                        ? LumiTheme.STATUS_BORDER : LumiTheme.CHIP_BORDER);
        graphics.drawCenteredString(
                font,
                font.plainSubstrByWidth(valueText, valueWidth - 8),
                valueX + valueWidth / 2, valueY + 5,
                selected ? LumiTheme.ACCENT : LumiTheme.MUTED);
    }

    private void drawToggle(GuiGraphics graphics, int width) {
        float position = toggleMotion.toward(selected ? 1 : 0);
        int x = getX() + getWidth() - width - 6;
        int y = getY() + (getHeight() - 14) / 2;
        int track = LumiTheme.CHIP_BORDER;
        graphics.fill(x + 2, y, x + width - 2, y + 14, track);
        graphics.fill(x, y + 2, x + width, y + 12, track);
        if (active && position > 0) {
            int accent = LumiTheme.withOpacity(LumiTheme.ACCENT, position * .65F);
            graphics.fill(x + 2, y, x + width - 2, y + 14, accent);
            graphics.fill(x, y + 2, x + width, y + 12, accent);
        }
        int thumbWidth = Math.min(10, Math.max(0, width - 4));
        int thumbX = x + 2 + Math.round(Math.max(0, width - thumbWidth - 4) * position);
        graphics.fill(thumbX, y + 2, thumbX + thumbWidth, y + 12,
                active ? LumiTheme.TEXT : LumiTheme.MUTED);
    }

    static int toggleWidth(int rowWidth) {
        return rowWidth < 30 ? 0 : Math.min(36, rowWidth - 24);
    }

    static int valueWidth(int rowWidth, int textWidth) {
        return Math.min(Math.max(0, rowWidth - 24),
                Math.max(MIN_VALUE_WIDTH, Math.max(0, textWidth) + 12));
    }
}
