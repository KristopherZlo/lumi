package io.github.lumi.client.ui;

import io.github.lumi.client.LumiHotkeys;
import io.github.lumi.client.onboarding.OnboardingHoldGate;
import io.github.lumi.client.onboarding.OnboardingTour;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** Replayable nine-step hands-on introduction retained from legacy Lumi. */
public final class LumiOnboardingScreen extends LumiLegacyModalScreen {
    private static final int PANEL_HEIGHT = 224;
    private final Screen returnScreen;
    private final Screen background;
    private final OnboardingTour tour;
    private final Actions actions;
    private final OnboardingHoldGate holdGate = new OnboardingHoldGate();
    private final OnboardingSpotlightLayout spotlights =
            new OnboardingSpotlightLayout();
    private final OnboardingScreenRenderer renderer =
            new OnboardingScreenRenderer();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private OnboardingSpotlightLayout.Placement spotlight;
    private long lastHoldSampleMillis;
    private boolean completionSent;

    public LumiOnboardingScreen(Screen parent) {
        this(parent, () -> { });
    }

    public LumiOnboardingScreen(Screen parent, Runnable completed) {
        this(parent, parent, new OnboardingTour(), new Actions(
                ignored -> { }, (screen, saved) -> { },
                ignored -> parent, ignored -> { }, completed));
    }

    public LumiOnboardingScreen(
            Screen returnScreen,
            Screen background,
            OnboardingTour tour,
            Actions actions) {
        super(background, Component.translatable("luma.screen.onboarding.title"));
        this.returnScreen = returnScreen;
        this.background = background;
        this.tour = Objects.requireNonNull(tour, "tour");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        holdGate.reset();
        lastHoldSampleMillis = 0L;
        if (tour.current().spotlight()) {
            initSpotlight();
        } else {
            initPanel();
        }
    }

    private void initPanel() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelHeight = fittedPanelHeight(height);
        panelY = Math.max(12, (height - panelHeight) / 2);
        int actionY = panelY + panelActionOffset(panelHeight);
        addBack(panelX + 16, actionY);
        OnboardingTour.Page page = tour.current();
        if (page.worldStep() || page.kind() == OnboardingTour.Kind.INFO) {
            addLegacyButton(
                    panelX + panelWidth - 96, actionY, 80,
                    Component.translatable("luma.action.next"),
                    this::advanceButton, LumiLegacyButton.Kind.PRIMARY);
        } else if (shortcutUnbound(page)) {
            addLegacyButton(
                    panelX + panelWidth - 184, actionY, 80,
                    Component.translatable("luma.action.open_controls"),
                    this::openControls, LumiLegacyButton.Kind.NORMAL);
            addLegacyButton(
                    panelX + panelWidth - 96, actionY, 80,
                    Component.translatable("luma.action.skip"),
                    this::skipShortcut, LumiLegacyButton.Kind.PRIMARY);
        }
    }

    private void initSpotlight() {
        spotlight = background instanceof LumiDashboardScreen dashboard
                ? spotlights.place(
                        dashboard.onboardingTarget(tour.current().kind()),
                        width, height)
                : spotlights.place(tour.current().kind(), width, height);
        var prompt = spotlight.prompt();
        addBack(prompt.x() + 10, prompt.bottom() - 28);
        addLegacyButton(
                prompt.right() - 90, prompt.bottom() - 28, 80,
                Component.translatable("luma.action.next"),
                this::advanceSpotlight, LumiLegacyButton.Kind.PRIMARY);
    }

    private void addBack(int x, int y) {
        LumiLegacyButton back = addLegacyButton(
                x, y, 80, Component.translatable("luma.action.back"),
                () -> {
                    tour.previous();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
        back.active = tour.canGoBack();
    }

    @Override
    public void tick() {
        super.tick();
        if (background instanceof LumiDashboardScreen dashboard) {
            dashboard.tick();
        }
        OnboardingTour.Page page = tour.current();
        if (!page.holdStep() || shortcutUnbound(page)) {
            holdGate.reset();
            lastHoldSampleMillis = 0L;
            return;
        }
        boolean held = page.bindings().stream().allMatch(binding ->
                LumiHotkeys.bindingDown(
                        minecraft.options.keyMappings, binding));
        long now = Util.getMillis();
        long elapsed = held && lastHoldSampleMillis > 0L
                ? Math.min(100L, now - lastHoldSampleMillis) : 0L;
        lastHoldSampleMillis = held ? now : 0L;
        if (holdGate.update(held, elapsed)) {
            completeHold(page.kind());
        }
    }

    private void completeHold(OnboardingTour.Kind kind) {
        holdGate.reset();
        lastHoldSampleMillis = 0L;
        switch (kind) {
            case HOLD_SAVE -> actions.save().open(this, () -> {
                tour.advanceQuickSave();
                rebuildWidgets();
            });
            case HOLD_DASHBOARD -> {
                tour.next();
                openDashboard();
            }
            case HOLD_HOTKEYS -> {
                complete();
                actions.hotkeys().accept(workspaceBackground());
            }
            default -> {
            }
        }
    }

    private void advanceButton() {
        if (tour.current().worldStep()) {
            actions.worldStep().accept(tour);
            minecraft.setScreen(null);
            return;
        }
        tour.next();
        rebuildWidgets();
    }

    private void advanceSpotlight() {
        tour.next();
        rebuildWidgets();
    }

    private void skipShortcut() {
        switch (tour.current().kind()) {
            case HOLD_DASHBOARD -> {
                tour.next();
                openDashboard();
            }
            case HOLD_HOTKEYS -> onClose();
            default -> {
                tour.next();
                rebuildWidgets();
            }
        }
    }

    private void openDashboard() {
        Screen dashboard = actions.dashboard().apply(returnScreen);
        minecraft.setScreen(dashboard);
        minecraft.setScreen(new LumiOnboardingScreen(
                returnScreen, dashboard, tour, actions));
    }

    private void openControls() {
        minecraft.setScreen(new ControlsScreen(this, minecraft.options));
    }

    private boolean shortcutUnbound(OnboardingTour.Page page) {
        return page.bindings().stream().anyMatch(binding ->
                LumiHotkeys.bindingUnbound(
                        minecraft.options.keyMappings, binding));
    }

    private Screen workspaceBackground() {
        return background instanceof LumiDashboardScreen
                ? background : returnScreen;
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            if (tour.current().spotlight()) {
                renderer.spotlight(
                        graphics, font, tour, spotlight, width, height);
            } else {
                graphics.fill(
                        0, 0, width, height, LegacyLumiTheme.BACKDROP);
                renderer.panel(
                        graphics, font, minecraft.options.keyMappings,
                        tour, holdGate,
                        panelX, panelY, panelWidth, panelHeight);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return event.key() == GLFW.GLFW_KEY_ESCAPE
                || super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() {
        return false;
    }

    @Override public void onClose() {
        complete();
        minecraft.setScreen(returnScreen);
    }

    private void complete() {
        if (!completionSent) {
            completionSent = true;
            actions.completed().run();
        }
    }

    static int fittedPanelHeight(int screenHeight) {
        return Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 24));
    }

    static int panelActionOffset(int panelHeight) {
        return panelHeight - 30;
    }

    public record Actions(
            Consumer<OnboardingTour> worldStep,
            SaveOpener save,
            Function<Screen, Screen> dashboard,
            Consumer<Screen> hotkeys,
            Runnable completed) {
        public Actions {
            Objects.requireNonNull(worldStep, "worldStep");
            Objects.requireNonNull(save, "save");
            Objects.requireNonNull(dashboard, "dashboard");
            Objects.requireNonNull(hotkeys, "hotkeys");
            Objects.requireNonNull(completed, "completed");
        }
    }

    @FunctionalInterface
    public interface SaveOpener {
        void open(Screen parent, Runnable saved);
    }
}
