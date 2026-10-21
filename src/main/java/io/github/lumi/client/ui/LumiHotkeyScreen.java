package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Read-only table of the actual registered Lumi shortcuts. */
public final class LumiHotkeyScreen extends LumiModalScreen {
    private final Screen parent;
    private final List<LumiHotkeys.Shortcut> shortcuts;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;
    private final SecretCode secretCode = new SecretCode();
    private int lastCodeKey = -1;

    public LumiHotkeyScreen(Screen parent, List<LumiHotkeys.Shortcut> shortcuts) {
        super(Component.translatable("luma.screen.hotkeys.title"));
        this.parent = parent;
        this.shortcuts = List.copyOf(shortcuts);
    }

    @Override
    protected void init() {
        beginScreenInit();
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        panelHeight = fittedPanelHeight(height, shortcuts.size());
        panelY = Math.max(12, (height - panelHeight) / 2);
        scroll = Math.min(scroll, Math.max(0, shortcuts.size() - visibleRows()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawString(font, title, panelX + 16, panelY + 17,
                LumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.hotkeys.help"),
                panelX + 16, panelY + 38, LumiTheme.MUTED, false);
        String modifier = LumiHotkeys.bindingLabel(
                minecraft.options.keyMappings, "key.lumi.action_modifier");
        int count = Math.min(visibleRows(), shortcuts.size() - scroll);
        for (int index = 0; index < count; index++) {
            LumiHotkeys.Shortcut shortcut = shortcuts.get(scroll + index);
            int y = panelY + 62 + index * 34;
            renderPanel(graphics,
                    panelX + 12, y - 5, panelWidth - 24, 30);
            graphics.drawString(font, shortcut.display(modifier),
                    panelX + 20, y + 3, LumiTheme.accent(), false);
            graphics.drawString(font, Component.translatable(shortcut.labelKey()),
                    panelX + 118, y, LumiTheme.TEXT, false);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            Component.translatable(shortcut.helpKey()).getString(),
                            panelWidth - 150),
                    panelX + 118, y + 12, LumiTheme.MUTED, false);
        }
        renderScrollbar(
                graphics, panelX + 8, panelY + 57, panelWidth - 14,
                Math.max(0, panelHeight - 85),
                shortcuts.size(), visibleRows(), scroll,
                value -> scroll = value);
        if (scroll + count == shortcuts.size()) {
            int x = panelX + (panelWidth - font.width(String.join(" ", SecretCode.SYMBOLS))) / 2;
            for (int index = 0; index < SecretCode.SYMBOLS.length; index++) {
                String symbol = SecretCode.SYMBOLS[index];
                graphics.drawString(font, symbol, x, panelY + panelHeight - 17,
                        index < secretCode.progress() ? 0xffffffff : 0x995c6068, false);
                x += font.width(symbol + " ");
            }
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics, render, partialTick);
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= panelY && y < panelY + panelHeight) {
            int maximum = Math.max(0, shortcuts.size() - visibleRows());
            int replacement = Math.max(0, Math.min(
                    maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) scroll = replacement;
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int visibleRows() {
        return visibleShortcutRows(panelHeight, shortcuts.size());
    }

    static int fittedPanelHeight(int screenHeight, int shortcutCount) {
        return Math.min(80 + shortcutCount * 34, Math.max(1, screenHeight - 24));
    }

    static int visibleShortcutRows(int panelHeight, int shortcutCount) {
        return Math.min(shortcutCount, Math.max(0, (panelHeight - 80) / 34));
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == lastCodeKey) return true;
        lastCodeKey = event.key();
        if (secretCode.press(event.key())) {
            lastCodeKey = -1;
            minecraft.setScreen(new LumiAstroInvadersScreen(this));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean keyReleased(KeyEvent event) {
        if (lastCodeKey == event.key()) lastCodeKey = -1;
        return super.keyReleased(event);
    }

    static final class SecretCode {
        private static final String[] SYMBOLS = {"↑", "↑", "↓", "↓", "←", "→", "←", "→", "B", "A"};
        private static final int[] CODE = {InputConstants.KEY_UP, InputConstants.KEY_UP,
                InputConstants.KEY_DOWN, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
                InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_B, InputConstants.KEY_A};
        private int length;

        boolean press(int key) {
            if (key != CODE[length]) { length = 0; return false; }
            boolean matched = ++length == CODE.length;
            if (matched) length = 0;
            return matched;
        }

        int progress() { return length; }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
