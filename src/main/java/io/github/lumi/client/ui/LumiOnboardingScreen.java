package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.OnboardingTour;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Replayable, non-destructive introduction to the core builder loop. */
public final class LumiOnboardingScreen extends Screen {
    private final Screen parent;
    private final OnboardingTour tour = new OnboardingTour();
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiOnboardingScreen(Screen parent) {
        super(Component.translatable("luma.screen.onboarding.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 210) / 2);
        Button back = Button.builder(Component.translatable("luma.action.back"), ignored -> {
            tour.previous();
            rebuildWidgets();
        }).bounds(panelX + 16, panelY + 170, 80, 20).build();
        back.active = !tour.first();
        addRenderableWidget(back);
        addRenderableWidget(Button.builder(
                Component.translatable(tour.last() ? "luma.action.finish" : "luma.action.next"),
                ignored -> {
                    if (tour.last()) {
                        onClose();
                    } else {
                        tour.next();
                        rebuildWidgets();
                    }
                }).bounds(panelX + panelWidth - 96, panelY + 170, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 206, 0xee15181d);
        graphics.drawString(font,
                Component.translatable("luma.onboarding.header",
                        tour.displayIndex(), OnboardingTour.pageCount()),
                panelX + 16, panelY + 18, 0xff8f9aa8, false);
        OnboardingTour.Page page = tour.current();
        graphics.drawString(font,
                Component.translatable("luma.onboarding.topic_" + page.id()),
                panelX + 16, panelY + 42, 0xffffd166, false);
        graphics.drawString(font, Component.translatable(page.titleKey()),
                panelX + 16, panelY + 62, 0xffffffff, false);
        int y = panelY + 86;
        for (var line : font.split(
                Component.translatable(page.helpKey()), panelWidth - 32)) {
            graphics.drawString(font, line, panelX + 16, y, 0xffaeb6c2, false);
            y += 12;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
