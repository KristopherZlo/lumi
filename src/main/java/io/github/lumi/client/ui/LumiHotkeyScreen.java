package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only table of the actual registered Lumi shortcuts. */
public final class LumiHotkeyScreen extends Screen {
    private final Screen parent;
    private final List<LumiHotkeys.Shortcut> shortcuts;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiHotkeyScreen(Screen parent, List<LumiHotkeys.Shortcut> shortcuts) {
        super(Component.translatable("luma.screen.hotkeys.title"));
        this.parent = parent;
        this.shortcuts = List.copyOf(shortcuts);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        int panelHeight = 76 + shortcuts.size() * 34;
        panelY = Math.max(12, (height - panelHeight) / 2);
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 76, panelY + 12, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelHeight = 76 + shortcuts.size() * 34;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
                0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 17, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.hotkeys.help"),
                panelX + 16, panelY + 38, 0xffaeb6c2, false);
        for (int index = 0; index < shortcuts.size(); index++) {
            LumiHotkeys.Shortcut shortcut = shortcuts.get(index);
            int y = panelY + 62 + index * 34;
            graphics.fill(panelX + 12, y - 5, panelX + panelWidth - 12, y + 25,
                    0xff20252c);
            graphics.drawString(font, "Alt + " + shortcut.key(),
                    panelX + 20, y + 3, 0xffffd166, false);
            graphics.drawString(font, Component.translatable(shortcut.labelKey()),
                    panelX + 118, y, 0xfff0f3f6, false);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            Component.translatable(shortcut.helpKey()).getString(),
                            panelWidth - 150),
                    panelX + 118, y + 12, 0xff8f9aa8, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
