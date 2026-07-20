package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.OnboardingController;
import io.github.lumi.client.onboarding.OnboardingEvent;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Replayable nine-step hands-on introduction driven by explicit events. */
public final class LumiOnboardingScreen extends LumiModalScreen {
    private static final int PANEL_HEIGHT = 224;
    private static final int NAVIGATION_WIDTH = 64;
    private final Screen returnScreen;
    private final Screen background;
    private final OnboardingController controller;
    private final Actions actions;
    private final OnboardingSpotlightLayout spotlights =
            new OnboardingSpotlightLayout();
    private final OnboardingScreenRenderer renderer =
            new OnboardingScreenRenderer();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private OnboardingSpotlightLayout.Placement spotlight;
    private boolean completionSent;

    public LumiOnboardingScreen(Screen parent) {
        this(parent, () -> { });
    }

    public LumiOnboardingScreen(Screen parent, Runnable completed) {
        this(parent, parent, new OnboardingController(), new Actions(
                ignored -> { }, (screen, saved) -> { },
                ignored -> parent, ignored -> { }, completed));
    }

    public LumiOnboardingScreen(
            Screen returnScreen,
            Screen background,
            OnboardingController controller,
            Actions actions) {
        super(background, Component.translatable("luma.screen.onboarding.title"));
        this.returnScreen = returnScreen;
        this.background = background;
        this.controller = Objects.requireNonNull(controller, "controller");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    protected void init() {
        beginScreenInit();
        if (controller.current().spotlight()) initSpotlight();
        else initPanel();
    }

    private void initPanel() {
        panelWidth = Math.min(390, width - 24);
        panelX = (width - panelWidth) / 2;
        panelHeight = fittedPanelHeight(height);
        panelY = Math.max(12, (height - panelHeight) / 2);
        addNavigation(panelX + 16, panelX + panelWidth - 16,
                panelY + panelActionOffset(panelHeight));
    }

    private void initSpotlight() {
        spotlight = background instanceof LumiDashboardScreen dashboard
                ? spotlights.place(
                        dashboard.onboardingTarget(controller.current().kind()),
                        width, height)
                : spotlights.place(controller.current().kind(), width, height);
        var prompt = spotlight.prompt();
        addNavigation(prompt.x() + 10, prompt.right() - 10,
                prompt.bottom() - 28);
    }

    private void addNavigation(int left, int right, int y) {
        LumiButton back = addButton(
                left, y, NAVIGATION_WIDTH,
                Component.translatable("luma.action.back"),
                () -> accept(new OnboardingEvent.Navigation(
                        OnboardingEvent.Direction.BACK)),
                LumiButton.Kind.NORMAL);
        back.active = controller.canGoBack();
        addButton(right - NAVIGATION_WIDTH * 2 - 8, y, NAVIGATION_WIDTH,
                Component.translatable("luma.action.skip"),
                () -> accept(new OnboardingEvent.Navigation(
                        OnboardingEvent.Direction.SKIP)),
                LumiButton.Kind.NORMAL);
        addButton(right - NAVIGATION_WIDTH, y, NAVIGATION_WIDTH,
                Component.translatable("luma.action.next"),
                () -> accept(new OnboardingEvent.Navigation(
                        OnboardingEvent.Direction.NEXT)),
                LumiButton.Kind.PRIMARY);
    }

    @Override
    public void tick() {
        super.tick();
        if (background instanceof LumiDashboardScreen dashboard) dashboard.tick();
    }

    public void accept(OnboardingEvent event) {
        execute(controller.handle(event));
    }

    private void execute(OnboardingController.Effect effect) {
        switch (effect) {
            case NONE -> { }
            case REFRESH, REOPEN -> rebuildWidgets();
            case ENTER_WORLD -> {
                actions.worldStep().accept(controller);
                minecraft.setScreen(null);
            }
            case OPEN_SAVE -> actions.save().open(
                    this, () -> accept(new OnboardingEvent.SaveCompleted()));
            case OPEN_DASHBOARD -> openDashboard();
            case OPEN_HOTKEYS -> {
                complete();
                actions.hotkeys().accept(workspaceBackground());
            }
            case COMPLETE -> {
                complete();
                minecraft.setScreen(returnScreen);
            }
        }
    }

    private void openDashboard() {
        Screen dashboard = actions.dashboard().apply(returnScreen);
        minecraft.setScreen(new LumiOnboardingScreen(
                returnScreen, dashboard, controller, actions));
    }

    private Screen workspaceBackground() {
        return background instanceof LumiDashboardScreen
                ? background : returnScreen;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (spotlight == null) return false;
        double x = virtualCoordinate(click.x());
        double y = virtualCoordinate(click.y());
        var hole = spotlight.hole();
        if (x < hole.x() || x >= hole.right()
                || y < hole.y() || y >= hole.bottom()) return false;
        boolean activated = background != null
                && background.mouseClicked(click, doubled);
        if (activated) {
            accept(new OnboardingEvent.SpotlightActivated(
                    controller.current().kind()));
            if (minecraft.screen == null) {
                minecraft.setScreen(new LumiOnboardingScreen(
                        returnScreen, background, controller, actions));
            }
        }
        return activated;
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            if (controller.current().spotlight()) {
                alignNavigation(0, 0, width);
                renderer.spotlight(
                        graphics, font, controller, spotlight, width, height);
            } else {
                alignNavigation(panelX, panelY, panelWidth);
                graphics.fill(0, 0, width, height, LumiTheme.BACKDROP);
                renderer.panel(
                        graphics, font, minecraft.options.keyMappings,
                        controller, panelX, panelY, panelWidth, panelHeight);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(returnScreen);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
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
            Consumer<OnboardingController> worldStep,
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
