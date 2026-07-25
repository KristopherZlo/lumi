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

    static LumiSettingRow toggle(
            int x, int y, int width,
            Component label, Component description, boolean selected,
            OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.translatable(selected ? "options.on" : "options.off"),
                0, selected, false, onPress);
    }

    static LumiSettingRow action(
            int x, int y, int width,
            Component label, Component description, OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.empty(), 0, false, true, onPress);
    }

    static LumiSettingRow choice(
            int x, int y, int width,
            Component label, Component description,
            int reservedControlWidth, OnPress onPress) {
        return new LumiSettingRow(
                x, y, width, label, description,
                Component.empty(), Math.max(0, reservedControlWidth),
                false, false, onPress);
    }

    private LumiSettingRow(
            int x, int y, int width,
            Component label, Component description, Component value,
            int reservedControlWidth,
            boolean selected, boolean destructive, OnPress onPress) {
        super(x, y, width, HEIGHT, label, onPress, DEFAULT_NARRATION);
        this.label = label;
        this.description = description;
        this.value = value;
        this.reservedControlWidth = reservedControlWidth;
        this.selected = selected;
        this.destructive = destructive;
        if (!description.getString().isBlank()) {
            setTooltip(Tooltip.create(description));
        }
    }

    @Override
    protected void renderContents(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = active && isHoveredOrFocused();
        LumiTheme.outlined(
                graphics, getX(), getY(), getWidth(), getHeight(),
                highlighted ? LumiTheme.CHIP : LumiTheme.INSET,
                isFocused()
                        ? LumiTheme.ACCENT : LumiTheme.INSET_BORDER);

        var font = Minecraft.getInstance().font;
        String valueText = value.getString();
        int valueWidth = valueText.isEmpty()
                ? reservedControlWidth : valueWidth(getWidth(), font.width(value));
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

    static int valueWidth(int rowWidth, int textWidth) {
        return Math.min(Math.max(0, rowWidth - 24),
                Math.max(MIN_VALUE_WIDTH, Math.max(0, textWidth) + 12));
    }
}
