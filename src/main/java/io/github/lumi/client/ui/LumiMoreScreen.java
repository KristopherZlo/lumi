package io.github.lumi.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary client-only tools kept away from the main history workflow. */
public final class LumiMoreScreen extends Screen {
    private final Screen parent;
    private final Runnable onboarding;
    private final Runnable hotkeys;
    private final Runnable thanks;
    private final Runnable diagnostics;
    private final Runnable settings;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiMoreScreen(
            Screen parent,
            Runnable onboarding,
            Runnable hotkeys,
            Runnable thanks,
            Runnable diagnostics,
            Runnable settings) {
        super(Component.translatable("luma.screen.more.title"));
        this.parent = parent;
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.hotkeys = Objects.requireNonNull(hotkeys, "hotkeys");
        this.thanks = Objects.requireNonNull(thanks, "thanks");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected void init() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 278) / 2);
        button("luma.more.onboarding_title", onboarding, 66);
        button("luma.hotkeys.title", hotkeys, 100);
        button("luma.more.special_thanks_title", thanks, 134);
        button("luma.action.open_diagnostics", diagnostics, 168);
        button("luma.action.settings", settings, 202);
        button("luma.action.close", this::onClose, 236);
    }

    private void button(String key, Runnable action, int offset) {
        addRenderableWidget(Button.builder(Component.translatable(key), ignored -> action.run())
                .bounds(panelX + 16, panelY + offset, panelWidth - 32, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 278, 0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 18, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.more.help"),
                panelX + 16, panelY + 40, 0xffaeb6c2, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
