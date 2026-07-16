package io.github.lumi.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bundled credits; opening this screen performs no network request. */
public final class LumiSpecialThanksScreen extends Screen {
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
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 76, panelY + 12, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 190, 0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 18, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.special_thanks.help"),
                panelX + 16, panelY + 42, 0xffaeb6c2, false);
        entry(graphics, panelY + 76, "ImZlo",
                Component.translatable("luma.special_thanks.zlo_role"));
        entry(graphics, panelY + 122, "Nayakochii", Component.literal("Tester"));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void entry(GuiGraphics graphics, int y, String name, Component role) {
        graphics.fill(panelX + 16, y, panelX + panelWidth - 16, y + 34, 0xff20252c);
        graphics.drawString(font, name, panelX + 26, y + 7, 0xfff0f3f6, false);
        graphics.drawString(font, role, panelX + 26, y + 19, 0xff8f9aa8, false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
