package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.OnboardingTour;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Replayable, non-destructive introduction to the core builder loop. */
public final class LumiOnboardingScreen extends LumiLegacyModalScreen {
    private final Screen parent;
    private final Runnable completed;
    private final OnboardingTour tour = new OnboardingTour();
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiOnboardingScreen(Screen parent) {
        this(parent, () -> { });
    }

    public LumiOnboardingScreen(Screen parent, Runnable completed) {
        super(Component.translatable("luma.screen.onboarding.title"));
        this.parent = parent;
        this.completed = java.util.Objects.requireNonNull(completed, "completed");
    }

    @Override
    protected void init() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 210) / 2);
        LumiLegacyButton back = addLegacyButton(
                panelX + 16, panelY + 170, 80,
                Component.translatable("luma.action.back"), () -> {
                    tour.previous();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
        back.active = !tour.first();
        addLegacyButton(
                panelX + panelWidth - 96, panelY + 170, 80,
                Component.translatable(tour.last() ? "luma.action.finish" : "luma.action.next"),
                () -> {
                    if (tour.last()) {
                        onClose();
                    } else {
                        tour.next();
                        rebuildWidgets();
                    }
                }, LumiLegacyButton.Kind.PRIMARY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, 206);
        LegacyLumiTheme.outlined(graphics,
                panelX + 12, panelY + 36, panelWidth - 24, 118,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
        graphics.drawString(font,
                Component.translatable("luma.onboarding.header",
                        tour.displayIndex(), OnboardingTour.pageCount()),
                panelX + 16, panelY + 18, LegacyLumiTheme.MUTED, false);
        OnboardingTour.Page page = tour.current();
        graphics.drawString(font,
                Component.translatable("luma.onboarding.topic_" + page.id()),
                panelX + 24, panelY + 48, LegacyLumiTheme.ACCENT, false);
        graphics.drawString(font, Component.translatable(page.titleKey()),
                panelX + 24, panelY + 68, LegacyLumiTheme.TEXT, false);
        int y = panelY + 92;
        for (var line : font.split(
                Component.translatable(page.helpKey()), panelWidth - 48)) {
            graphics.drawString(font, line, panelX + 24, y,
                    LegacyLumiTheme.MUTED, false);
            y += 12;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        completed.run();
        minecraft.setScreen(parent);
    }
}
