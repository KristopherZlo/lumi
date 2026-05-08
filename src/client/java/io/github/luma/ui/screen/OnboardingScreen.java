package io.github.luma.ui.screen;

import io.github.luma.client.input.LumiShortcutSuppressingScreen;
import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class OnboardingScreen extends LumaScreen implements LumiShortcutSuppressingScreen {

    private final Screen parent;
    private final String projectName;
    private final String variantId;
    private final String statusKey;
    private final ClientOnboardingService onboardingService;
    private final boolean openWorkspaceOnComplete;
    private final ScreenRouter router = new ScreenRouter();
    private final UndoRedoKeyController undoRedoController = new UndoRedoKeyController();
    private final OnboardingTour tour;

    public OnboardingScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready", new ClientOnboardingService());
    }

    public OnboardingScreen(Screen parent, String projectName, ClientOnboardingService onboardingService) {
        this(parent, projectName, "", "luma.status.project_ready", onboardingService);
    }

    public OnboardingScreen(
            Screen parent,
            String projectName,
            String variantId,
            String statusKey,
            ClientOnboardingService onboardingService
    ) {
        this(parent, projectName, variantId, statusKey, onboardingService, new OnboardingTour(), true);
    }

    public OnboardingScreen(
            Screen parent,
            String projectName,
            String variantId,
            String statusKey,
            ClientOnboardingService onboardingService,
            OnboardingTour tour,
            boolean openWorkspaceOnComplete
    ) {
        super(Component.translatable("luma.screen.onboarding.title"));
        this.parent = parent;
        this.projectName = projectName;
        this.variantId = variantId == null ? "" : variantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService;
        this.tour = tour == null ? new OnboardingTour() : tour;
        this.openWorkspaceOnComplete = openWorkspaceOnComplete;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(this.tour.hidden() ? Surface.flat(0x00000000) : LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);
        if (!this.tour.hidden()) {
            root.child(this.tour.panel(this.width, this::handleTourTransition));
        }
    }

    @Override
    public void onClose() {
        this.completeAndOpenWorkspace();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isEscapeKey(event)) {
            return true;
        }
        return true;
    }

    @Override
    public boolean suppressesLumiShortcuts() {
        return true;
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    @Override
    protected void onLumaTick() {
        this.handleTourTransition(this.tour.tick());
    }

    private void handleTourTransition(OnboardingTour.Transition transition) {
        if (transition == null || transition == OnboardingTour.Transition.NONE) {
            return;
        }
        switch (transition) {
            case REBUILD -> this.rebuild();
            case OPEN_WORKSPACE -> this.openWorkspaceWithOnboarding();
            case CLOSE_WORKSPACE -> this.closeWorkspaceForWorldStep();
            case OPEN_CONTROLS -> this.openControls();
            case EXECUTE_UNDO -> {
                this.executeUndo();
                this.rebuild();
            }
            case EXECUTE_REDO -> {
                this.executeRedo();
                this.rebuild();
            }
            case COMPLETE -> this.completeAndOpenWorkspace();
            case NONE -> {
            }
        }
    }

    private void openControls() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new ControlsScreen(this, client.options));
    }

    private void completeAndOpenWorkspace() {
        this.onboardingService.markCompleted();
        if (this.openWorkspaceOnComplete) {
            this.router.openProjectSkippingOnboarding(this.parent, this.projectName, this.variantId, this.statusKey);
        } else {
            Minecraft.getInstance().setScreen(this.parent);
        }
    }

    private void openWorkspaceWithOnboarding() {
        Minecraft.getInstance().setScreen(new ProjectScreen(
                this.parent,
                this.projectName,
                this.variantId,
                this.statusKey,
                this.onboardingService,
                this.tour
        ));
    }

    private void closeWorkspaceForWorldStep() {
        Minecraft.getInstance().setScreen(null);
    }

    private void executeUndo() {
        this.undoRedoController.undo(Minecraft.getInstance());
    }

    private void executeRedo() {
        this.undoRedoController.redo(Minecraft.getInstance());
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    static boolean isEscapeKey(KeyEvent event) {
        return event != null && event.key() == GLFW.GLFW_KEY_ESCAPE;
    }
}
