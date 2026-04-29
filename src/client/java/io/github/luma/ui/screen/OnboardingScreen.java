package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.OnboardingFlowState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OnboardingScreen extends LumaScreen {

    private static final int MIN_DIALOG_WIDTH = 260;
    private static final int MAX_DIALOG_WIDTH = 380;
    private static final List<Page> PAGES = List.of(
            new Page("welcome", "luma.onboarding.welcome_title", "luma.onboarding.welcome_help"),
            new Page("save", "luma.onboarding.save_title", "luma.onboarding.save_help"),
            new Page("undo", "luma.onboarding.undo_title", "luma.onboarding.undo_help"),
            new Page("restore", "luma.onboarding.restore_title", "luma.onboarding.restore_help"),
            new Page("more", "luma.onboarding.more_title", "luma.onboarding.more_help")
    );

    private final Screen parent;
    private final String projectName;
    private final String variantId;
    private final String statusKey;
    private final ClientOnboardingService onboardingService;
    private final ScreenRouter router = new ScreenRouter();
    private OnboardingFlowState flow = OnboardingFlowState.first(PAGES.size());

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

        FlowLayout frame = LumaUi.modalFrame(this.dialogWidth());
        root.child(frame);

        Page page = PAGES.get(this.flow.pageIndex());
        frame.child(this.header(page));
        frame.child(LumaUi.value(Component.translatable(page.titleKey())));
        frame.child(LumaUi.caption(Component.translatable(page.helpKey())));
        frame.child(this.actions());
    }

    @Override
    public void onClose() {
        this.completeAndOpenWorkspace();
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private FlowLayout header(Page page) {
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(5);
        header.child(LumaUi.stepBadge(Component.translatable(
                "luma.onboarding.step",
                this.flow.displayIndex(),
                this.flow.pageCount()
        )));
        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(2);
        text.child(LumaUi.caption(Component.translatable("luma.onboarding.label")));
        text.child(LumaUi.accent(Component.translatable("luma.onboarding.topic_" + page.id())));
        header.child(text);
        return header;
    }

    private FlowLayout actions() {
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent back = LumaUi.button(Component.translatable("luma.action.back"), button -> this.previousPage());
        back.active(!this.flow.firstPage());
        actions.child(back);
        actions.child(LumaUi.button(Component.translatable("luma.action.skip_for_now"), button -> this.completeAndOpenWorkspace()));
        if (this.flow.lastPage()) {
            actions.child(LumaUi.primaryButton(Component.translatable("luma.action.open_lumi"), button -> this.completeAndOpenWorkspace()));
        } else {
            actions.child(LumaUi.primaryButton(Component.translatable("luma.action.next"), button -> this.nextPage()));
        }
        return actions;
    }

    private void nextPage() {
        this.flow = this.flow.next();
        this.rebuild();
    }

    private void previousPage() {
        this.flow = this.flow.previous();
        this.rebuild();
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

    private int dialogWidth() {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, this.width - 20));
    }

    private record Page(String id, String titleKey, String helpKey) {
    }
}
