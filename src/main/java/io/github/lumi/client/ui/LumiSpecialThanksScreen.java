package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bundled credits; opening this screen performs no network request. */
public final class LumiSpecialThanksScreen extends LumiLegacyModalScreen {
    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiSpecialThanksScreen(Screen parent) {
        super(Component.translatable("luma.screen.special_thanks.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 190) / 2);
        addLegacyButton(panelX + panelWidth - 76, panelY + 10, 60,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, 190);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.special_thanks.help"),
                panelX + 16, panelY + 42, LegacyLumiTheme.MUTED, false);
        entry(graphics, panelY + 76, "ImZlo",
                Component.translatable("luma.special_thanks.zlo_role"));
        entry(graphics, panelY + 122, "Nayakochii", Component.literal("Tester"));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void entry(GuiGraphics graphics, int y, String name, Component role) {
        renderLegacyPanel(graphics, panelX + 16, y, panelWidth - 32, 34);
        graphics.drawString(font, name, panelX + 26, y + 7,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, role, panelX + 26, y + 19,
                LegacyLumiTheme.MUTED, false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
