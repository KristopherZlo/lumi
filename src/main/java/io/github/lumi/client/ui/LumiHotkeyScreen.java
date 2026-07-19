package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only table of the actual registered Lumi shortcuts. */
public final class LumiHotkeyScreen extends LumiLegacyModalScreen {
    private final Screen parent;
    private final List<LumiHotkeys.Shortcut> shortcuts;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;

    public LumiHotkeyScreen(Screen parent, List<LumiHotkeys.Shortcut> shortcuts) {
        super(Component.translatable("luma.screen.hotkeys.title"));
        this.parent = parent;
        this.shortcuts = List.copyOf(shortcuts);
    }

    @Override
    protected void init() {
        beginLegacyInit();
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        panelHeight = fittedPanelHeight(height, shortcuts.size());
        panelY = Math.max(12, (height - panelHeight) / 2);
        scroll = Math.min(scroll, Math.max(0, shortcuts.size() - visibleRows()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawString(font, title, panelX + 16, panelY + 17,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.hotkeys.help"),
                panelX + 16, panelY + 38, LegacyLumiTheme.MUTED, false);
        String modifier = LumiHotkeys.bindingLabel(
                minecraft.options.keyMappings, "key.lumi.action_modifier");
        int count = Math.min(visibleRows(), shortcuts.size() - scroll);
        for (int index = 0; index < count; index++) {
            LumiHotkeys.Shortcut shortcut = shortcuts.get(scroll + index);
            int y = panelY + 62 + index * 34;
            renderLegacyPanel(graphics,
                    panelX + 12, y - 5, panelWidth - 24, 30);
            graphics.drawString(font, shortcut.display(modifier),
                    panelX + 20, y + 3, LegacyLumiTheme.ACCENT, false);
            graphics.drawString(font, Component.translatable(shortcut.labelKey()),
                    panelX + 118, y, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            Component.translatable(shortcut.helpKey()).getString(),
                            panelWidth - 150),
                    panelX + 118, y + 12, LegacyLumiTheme.MUTED, false);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
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
        return Math.min(76 + shortcutCount * 34, Math.max(1, screenHeight - 24));
    }

    static int visibleShortcutRows(int panelHeight, int shortcutCount) {
        return Math.min(shortcutCount, Math.max(0, (panelHeight - 54) / 34));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
