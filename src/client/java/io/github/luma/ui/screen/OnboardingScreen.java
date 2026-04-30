package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class OnboardingScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String variantId;
    private final String statusKey;
    private final ClientOnboardingService onboardingService;
    private final ScreenRouter router = new ScreenRouter();
    private final OnboardingTour tour = new OnboardingTour();

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
        super(Component.translatable("luma.screen.onboarding.title"));
        this.parent = parent;
        this.projectName = projectName;
        this.variantId = variantId == null ? "" : variantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);
        root.child(this.tour.panel(this.width, this::handleTourTransition));
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
        return super.keyPressed(event);
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
            case OPEN_WORKSPACE -> this.router.openProjectWithOnboarding(
                    this.parent,
                    this.projectName,
                    this.variantId,
                    this.statusKey,
                    this.onboardingService,
                    this.tour
            );
            case COMPLETE -> this.completeAndOpenWorkspace();
            case NONE -> {
            }
        }
    }

    private void completeAndOpenWorkspace() {
        this.onboardingService.markCompleted();
        this.router.openProjectSkippingOnboarding(this.parent, this.projectName, this.variantId, this.statusKey);
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
