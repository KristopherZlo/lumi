package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.screen.section.ProjectScreenSections;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.StackLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class ProjectScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ProjectScreenSections sections = new ProjectScreenSections(this.actionController, new SectionActions());
    private final ClientOnboardingService onboardingService;
    private OnboardingTour onboardingTour;
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ProjectHomeViewState state = new ProjectHomeViewState(
            null,
            List.of(),
            List.of(),
            io.github.luma.domain.model.PendingChangeSummary.empty(),
            false,
            null,
            null,
            "luma.status.project_ready"
    );
    private String statusKey;
    private String selectedVariantId = "";
    private boolean showAllSaves = false;
    private String pendingRestoreVariantId = "";
    private String pendingRestoreVersionId = "";
    private int refreshCooldown = 0;

    public ProjectScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready");
    }

    public ProjectScreen(Screen parent, String projectName, String statusKey) {
        this(parent, projectName, "", statusKey);
    }

    public ProjectScreen(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        this(parent, projectName, selectedVariantId, statusKey, new ClientOnboardingService(), null);
    }

    public ProjectScreen(
            Screen parent,
            String projectName,
            String selectedVariantId,
            String statusKey,
            ClientOnboardingService onboardingService,
            OnboardingTour onboardingTour
    ) {
        super(Component.translatable("luma.screen.project.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.selectedVariantId = selectedVariantId == null ? "" : selectedVariantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService == null ? new ClientOnboardingService() : onboardingService;
        this.onboardingTour = onboardingTour;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.stateController.loadState(this.projectName, this.statusKey, false);
        this.ensureSelectedVariant();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        if (this.state.project() == null) {
            FlowLayout frame = LumaUi.screenFrame();
            root.child(frame);
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        StackLayout stack = UIContainers.stack(Sizing.fill(100), Sizing.fill(100));
        root.child(stack);

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.simple.workspace_title", this.projectName),
                this.state.project(),
                this.state.variants()
        );
        stack.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.HISTORY);
        if (this.shouldShowStatusBanner()) {
            window.content().child(LumaUi.statusBanner(this.bannerText()));
        }

        ProjectScreenSections.Model model = this.sectionModel();
        FlowLayout confirmation = this.sections.initialRestoreConfirmationSection(model);
        if (confirmation != null) {
            window.content().child(confirmation);
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        body.child(this.sections.buildSection(model));
        body.child(this.sections.historySection(model));
        body.child(LumaUi.bottomSpacer());

        if (this.onboardingTour != null) {
            stack.child(this.onboardingOverlay());
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.onboardingTour != null && OnboardingScreen.isEscapeKey(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private ProjectScreenSections.Model sectionModel() {
        return new ProjectScreenSections.Model(
                this.projectName,
                this.state,
                this.width,
                this.selectedVariantId,
                this.showAllSaves,
                this.pendingRestoreVariantId,
                this.pendingRestoreVersionId,
                this.selectedLumiBounds()
        );
    }

    private void executeRestore(ProjectVariant variant, ProjectVersion version) {
        if (variant == null || version == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        this.refresh(this.actionController.restoreVersion(this.projectName, version.id()));
    }

    private void executeSelectedRestore(ProjectVersion version, PartialRestoreMode mode, Bounds3i bounds) {
        if (version == null || bounds == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }
        PartialRestoreRequest request = new PartialRestoreRequest(
                this.projectName,
                version.id(),
                bounds,
                mode,
                PartialRestoreRegionSource.LUMI_REGION,
                this.client.getUser().getName(),
                Map.of()
        );
        this.refresh(this.actionController.partialRestore(request));
    }

    private void clearPendingRestore() {
        this.pendingRestoreVariantId = "";
        this.pendingRestoreVersionId = "";
    }

    private void ensureSelectedVariant() {
        if (this.state.project() == null) {
            return;
        }

        if (!this.selectedVariantId.isBlank()
                && ProjectUiSupport.variantFor(this.state.variants(), this.selectedVariantId) != null) {
            return;
        }
        this.selectedVariantId = this.state.project().activeVariantId();
    }

    private void refresh(String statusKey) {
        this.refresh(statusKey, true);
    }

    private void refresh(String statusKey, boolean preserveScroll) {
        double scrollProgress = this.currentScrollProgress();
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(preserveScroll ? scrollProgress : 0.0D);
    }

    @Override
    protected void onLumaTick() {
        if (this.onboardingTour != null && this.handleOnboardingTransition(this.onboardingTour.tick())) {
            return;
        }
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        ProjectHomeViewState refreshed = this.stateController.loadState(this.projectName, this.statusKey, false);
        String normalizedStatusKey = ScreenOperationStateSupport.normalizeStatusKey(
                this.statusKey,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatusKey.equals(this.statusKey)) {
            this.statusKey = normalizedStatusKey;
            refreshed = this.stateController.loadState(this.projectName, this.statusKey, false);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.refresh(this.statusKey);
        }
    }

    private FlowLayout onboardingOverlay() {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);
        overlay.child(this.onboardingTour.panel(this.width, this::handleOnboardingTransition));
        return overlay;
    }

    private boolean handleOnboardingTransition(OnboardingTour.Transition transition) {
        if (transition == null || transition == OnboardingTour.Transition.NONE) {
            return false;
        }
        switch (transition) {
            case REBUILD, OPEN_WORKSPACE -> this.refresh(this.statusKey);
            case COMPLETE -> this.completeOnboarding();
            case NONE -> {
            }
        }
        return true;
    }

    private void completeOnboarding() {
        this.onboardingService.markCompleted();
        this.onboardingTour = null;
        this.refresh(this.statusKey);
    }

    private Component bannerText() {
        return ScreenOperationStateSupport.bannerText(this.state.status(), this.state.operationSnapshot(), "luma.status.project_ready");
    }

    private boolean shouldShowStatusBanner() {
        return ScreenOperationStateSupport.shouldShowStatusBanner(
                this.state.status(),
                this.state.operationSnapshot(),
                "luma.status.project_ready"
        );
    }

    private Optional<Bounds3i> selectedLumiBounds() {
        if (this.state.project() == null) {
            return Optional.empty();
        }
        return LumiRegionSelectionController.getInstance().selectedBounds(
                this.projectName,
                this.state.project().dimensionId()
        );
    }

    private double currentScrollProgress() {
        return this.bodyScroll == null ? 0.0D : this.bodyScroll.progress();
    }

    private void restoreScroll(double scrollProgress) {
        if (this.bodyScroll != null) {
            this.bodyScroll.restoreProgress(scrollProgress);
        }
    }

    private final class SectionActions implements ProjectScreenSections.Actions {

        @Override
        public void openSave() {
            router.openSave(ProjectScreen.this, projectName);
        }

        @Override
        public void openCompare(String leftReference, String rightReference, String contextVersionId) {
            router.openCompare(ProjectScreen.this, projectName, leftReference, rightReference, contextVersionId);
        }

        @Override
        public void openVariants() {
            router.openVariants(ProjectScreen.this, projectName);
        }

        @Override
        public void openRecovery() {
            router.openRecovery(ProjectScreen.this, projectName);
        }

        @Override
        public void openSaveDetails(String versionId) {
            router.openSaveDetails(ProjectScreen.this, projectName, versionId);
        }

        @Override
        public void selectVariant(String variantId) {
            selectedVariantId = variantId;
            showAllSaves = false;
            refresh("luma.status.project_ready", false);
        }

        @Override
        public void toggleAllSaves() {
            showAllSaves = !showAllSaves;
            refresh("luma.status.project_ready");
        }

        @Override
        public void requestRestore(ProjectVariant variant, ProjectVersion version) {
            if (variant == null || version == null) {
                return;
            }
            pendingRestoreVariantId = variant.id();
            pendingRestoreVersionId = version.id();
            refresh("luma.status.restore_confirmation_required");
        }

        @Override
        public void cancelRestore() {
            clearPendingRestore();
            refresh("luma.status.project_ready");
        }

        @Override
        public void confirmRestore(ProjectVariant variant, ProjectVersion version) {
            clearPendingRestore();
            executeRestore(variant, version);
        }

        @Override
        public void confirmSelectedRestore(ProjectVersion version, PartialRestoreMode mode, Bounds3i bounds) {
            clearPendingRestore();
            executeSelectedRestore(version, mode, bounds);
        }

        @Override
        public void clearPendingRestore() {
            ProjectScreen.this.clearPendingRestore();
        }
    }
}
