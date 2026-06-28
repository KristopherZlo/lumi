package io.github.luma.ui.screen;

import io.github.luma.LumaMod;
import io.github.luma.client.onboarding.ClientOnboardingService;
import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.client.onboarding.ClientOnboardingFlowCoordinator;
import io.github.luma.client.input.LumiShortcutSuppressingScreen;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.client.update.UpdateCheckService;
import io.github.luma.client.update.UpdateProjectNotice;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.ContextualHelpPresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.controller.BranchCreationDialogStateFactory;
import io.github.luma.ui.controller.BranchCreationResult;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.onboarding.OnboardingSpotlightOverlay;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.screen.section.BranchCreationDialogView;
import io.github.luma.ui.screen.section.ProjectScreenSections;
import io.github.luma.ui.screen.section.RestoreConfirmationDialogView;
import io.github.luma.ui.screen.section.UpdateNoticeDialogView;
import io.github.luma.ui.state.BranchCreationDialogState;
import io.github.luma.ui.state.CompareLoadState;
import io.github.luma.ui.state.CompareViewState;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.StackLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

public final class ProjectScreen extends LumaScreen implements LumiShortcutSuppressingScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final CompareScreenController compareController = new CompareScreenController();
    private final BranchCreationDialogStateFactory branchDialogFactory = new BranchCreationDialogStateFactory();
    private final ScreenRouter router = new ScreenRouter();
    private final UpdateCheckService updateCheckService = UpdateCheckService.getInstance();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ProjectScreenSections sections = new ProjectScreenSections(this.actionController, new SectionActions());
    private final BranchCreationDialogView branchDialogView = new BranchCreationDialogView(this.actionController, new BranchDialogActions());
    private final RestoreConfirmationDialogView restoreDialogView = new RestoreConfirmationDialogView(new RestoreDialogActions());
    private final UpdateNoticeDialogView updateDialogView = new UpdateNoticeDialogView(new UpdateDialogActions());
    private final ClientOnboardingService onboardingService;
    private final ClientContextualHelpService contextualHelpService = new ClientContextualHelpService();
    private final boolean animateWindow;
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
    private String lastActiveVariantId = "";
    private boolean historyGraphVisible = false;
    private String historyTagFilter = "";
    private String tagEditorVersionId = "";
    private String tagEditorText = "";
    private String pendingRestoreVariantId = "";
    private String pendingRestoreVersionId = "";
    private String pendingBranchBaseVersionId = "";
    private String branchName = "";
    private boolean saveDialogVisible = false;
    private String saveDialogMessage = "";
    private String saveDialogTags = "";
    private TextBoxComponent activeTagInput;
    private String pendingCompareLeftReference = "";
    private String pendingCompareRightReference = "";
    private int refreshCooldown = 0;

    public ProjectScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready");
    }

    public ProjectScreen(Screen parent, String projectName, String statusKey) {
        this(parent, projectName, "", statusKey);
    }

    public ProjectScreen(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        this(parent, projectName, selectedVariantId, statusKey, true);
    }

    public ProjectScreen(Screen parent, String projectName, String selectedVariantId, String statusKey, boolean animateWindow) {
        this(parent, projectName, selectedVariantId, statusKey, new ClientOnboardingService(), null, animateWindow);
    }

    public ProjectScreen(
            Screen parent,
            String projectName,
            String selectedVariantId,
            String statusKey,
            ClientOnboardingService onboardingService,
            OnboardingTour onboardingTour
    ) {
        this(parent, projectName, selectedVariantId, statusKey, onboardingService, onboardingTour, true);
    }

    public ProjectScreen(
            Screen parent,
            String projectName,
            String selectedVariantId,
            String statusKey,
            ClientOnboardingService onboardingService,
            OnboardingTour onboardingTour,
            boolean animateWindow
    ) {
        super(Component.translatable("luma.screen.project.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.selectedVariantId = selectedVariantId == null ? "" : selectedVariantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.onboardingService = onboardingService == null ? new ClientOnboardingService() : onboardingService;
        this.onboardingTour = onboardingTour;
        this.animateWindow = animateWindow;
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
        OnboardingTour.SpotlightTarget spotlightTarget = this.onboardingTour == null
                ? OnboardingTour.SpotlightTarget.NONE
                : this.onboardingTour.workspaceSpotlightTarget();
        this.sections.prepareOnboardingSpotlight(spotlightTarget);

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.simple.workspace_title", this.projectName),
                this.state.project(),
                this.state.variants()
        );
        stack.child(this.animateWindow ? this.animateOnFirstOpen(window.root()) : window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.HISTORY);
        if (this.shouldShowStatusBanner()) {
            window.content().child(LumaUi.statusBanner(this.bannerText()));
        }

        ProjectScreenSections.Model model = this.sectionModel();

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        if (this.onboardingTour == null) {
            ContextualHelpPresenter contextualHelp = new ContextualHelpPresenter(
                    this.contextualHelpService,
                    () -> this.refresh(this.statusKey)
            );
            boolean hintAdded = contextualHelp.addHint(body, ClientContextualHelpHint.HISTORY);
            if (!hintAdded) {
                hintAdded = contextualHelp.addHint(body, ClientContextualHelpHint.SHORTCUTS);
            }
            if (!hintAdded && this.state.pendingChanges().isEmpty()) {
                contextualHelp.addHint(body, ClientContextualHelpHint.CLEAN_STATE);
            } else if (!hintAdded) {
                hintAdded = contextualHelp.addHint(body, ClientContextualHelpHint.SAVE);
                if (!hintAdded) {
                    contextualHelp.addHint(body, ClientContextualHelpHint.QUICK_ROLLBACK);
                }
            }
        }
        body.child(this.sections.buildSection(model));
        body.child(this.sections.historySection(model));
        body.child(LumaUi.bottomSpacer());

        BranchCreationDialogState branchDialog = this.branchDialogState();
        if (branchDialog.visible() && this.onboardingTour == null) {
            stack.child(this.branchDialogView.overlay(new BranchCreationDialogView.Model(
                    this.projectName,
                    this.width,
                    branchDialog,
                    this.shouldShowStatusBanner() ? this.bannerText() : null
            )));
        } else if (this.onboardingTour == null && !this.pendingRestoreVersionId.isBlank()) {
            this.restoreDialogModel(model).ifPresent(dialog -> stack.child(this.restoreDialogView.overlay(dialog)));
        } else if (this.onboardingTour == null && this.saveDialogVisible) {
            stack.child(this.saveDialogOverlay(model));
        } else if (this.onboardingTour == null) {
            this.updateNotice().ifPresent(notice -> stack.child(this.updateDialogView.overlay(
                    new UpdateNoticeDialogView.Model(this.width, notice)
            )));
        }

        if (this.onboardingTour != null && spotlightTarget != OnboardingTour.SpotlightTarget.NONE) {
            stack.child(new OnboardingSpotlightOverlay(
                    () -> this.sections.onboardingTargetComponent(spotlightTarget),
                    this.onboardingTour,
                    this::handleOnboardingTransition
            ));
        } else if (this.onboardingTour != null) {
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
        if (this.onboardingTour != null) {
            return true;
        }
        if (this.saveDialogVisible && OnboardingScreen.isEscapeKey(event)) {
            this.closeSaveDialog();
            return true;
        }
        if (this.saveDialogVisible && event.key() == GLFW.GLFW_KEY_TAB && this.acceptSaveDialogTagCompletion()) {
            return true;
        }
        if (this.branchDialogState().visible() && OnboardingScreen.isEscapeKey(event)) {
            this.closeBranchDialog();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && this.acceptTagCompletion()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean suppressesLumiShortcuts() {
        return this.onboardingTour != null;
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    public void refreshUpdateNotice() {
        this.refresh(this.statusKey);
    }

    private ProjectScreenSections.Model sectionModel() {
        return new ProjectScreenSections.Model(
                this.projectName,
                this.state,
                this.width,
                this.selectedVariantId,
                this.historyGraphVisible,
                this.historyTagFilter,
                this.tagEditorVersionId,
                this.tagEditorText,
                this.pendingRestoreVariantId,
                this.pendingRestoreVersionId,
                this.selectedLumiBounds()
        );
    }

    private BranchCreationDialogState branchDialogState() {
        return this.branchDialogFactory.create(this.state, this.pendingBranchBaseVersionId, this.branchName);
    }

    private Optional<UpdateProjectNotice> updateNotice() {
        return UpdateProjectNotice.from(this.updateCheckService.promptRelease());
    }

    private Optional<RestoreConfirmationDialogView.Model> restoreDialogModel(ProjectScreenSections.Model model) {
        if (model.pendingRestoreVersionId().isBlank()) {
            return Optional.empty();
        }

        ProjectVersion version = ProjectUiSupport.versionFor(model.state().versions(), model.pendingRestoreVersionId());
        ProjectVariant variant = ProjectUiSupport.variantFor(model.state().variants(), model.pendingRestoreVariantId());
        if (version == null || variant == null) {
            this.clearPendingRestore();
            return Optional.empty();
        }

        boolean operationActive = model.state().operationSnapshot() != null && !model.state().operationSnapshot().terminal();
        return Optional.of(new RestoreConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.restore.confirm_title", ProjectUiSupport.displayMessage(version)),
                Component.translatable("luma.restore.confirm_help"),
                Component.translatable(
                        "luma.restore.confirm_target",
                        ProjectUiSupport.displayVariantName(variant),
                        ProjectUiSupport.displayMessage(version)
                ),
                model.state().project().settings().safetySnapshotBeforeRestore(),
                version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT,
                model.lumiSelection().isPresent(),
                operationActive
        ));
    }

    private FlowLayout saveDialogOverlay(ProjectScreenSections.Model model) {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout modal = LumaUi.modalFrame(Math.min(380, Math.max(280, this.width - 40)));
        modal.child(LumaUi.closeHeader(Component.translatable("luma.screen.save.title"), button -> this.closeSaveDialog()));
        modal.child(LumaUi.caption(Component.translatable("luma.save.summary_help")));

        TextBoxComponent message = UIComponents.textBox(Sizing.fill(100), this.saveDialogMessage);
        message.setHint(Component.translatable("luma.save.name_help"));
        message.onChanged().subscribe(value -> this.saveDialogMessage = value == null ? "" : value);
        modal.child(LumaUi.formField(
                Component.translatable("luma.save.name_input"),
                null,
                message
        ));

        TextBoxComponent tags = UIComponents.textBox(Sizing.fill(100), this.saveDialogTags);
        tags.setHint(Component.translatable("luma.history.tags_input"));
        TagInputSupport.configure(tags, this.saveDialogTags, TagInputSupport.knownTags(this.state.versions()), true);
        FlowLayout tagInput = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        tagInput.gap(2);
        TagSuggestionComponent tagSuggestions = new TagSuggestionComponent(
                () -> this.saveDialogTags,
                () -> TagInputSupport.knownTags(this.state.versions()),
                true,
                accepted -> {
                    this.saveDialogTags = accepted;
                    tags.setValue(accepted);
                    tags.setCursorPosition(accepted.length());
                }
        );
        tags.onChanged().subscribe(value -> {
            this.saveDialogTags = TagInputSupport.limit(value);
            tagSuggestions.refresh();
        });
        this.activeTagInput = tags;
        tagInput.child(tags);
        tagInput.child(tagSuggestions);
        modal.child(LumaUi.formField(
                Component.translatable("luma.save.tags_title"),
                null,
                tagInput
        ));

        ProjectVersion activeHead = ProjectUiSupport.activeHead(
                model.state().project(),
                model.state().variants(),
                model.state().versions()
        );
        boolean operationActive = ScreenOperationStateSupport.blocksMutationActions(model.state().operationSnapshot());
        boolean canSave = !model.state().pendingChanges().isEmpty() && !operationActive;

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent save = LumaUi.primaryButton(Component.translatable("luma.action.save"), button -> this.startDialogSave(false));
        save.active(canSave);
        actions.child(save);

        ButtonComponent amend = LumaUi.button(Component.translatable("luma.action.amend_version"), button -> this.startDialogSave(true));
        amend.active(canSave && activeHead != null);
        actions.child(amend);
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.closeSaveDialog()));
        modal.child(actions);

        overlay.child(modal);
        return overlay;
    }

    public void openSaveDialog() {
        this.openSaveDialog("");
    }

    private void openSaveDialog(String initialMessage) {
        this.saveDialogVisible = true;
        this.saveDialogMessage = initialMessage == null ? "" : initialMessage;
        this.saveDialogTags = "";
        this.activeTagInput = null;
        this.refresh("luma.status.project_ready");
    }

    private void closeSaveDialog() {
        this.saveDialogVisible = false;
        this.activeTagInput = null;
        this.refresh("luma.status.project_ready");
    }

    private void startDialogSave(boolean amend) {
        String result = amend
                ? this.actionController.amendVersion(this.projectName, this.saveDialogMessage, ProjectVersionTags.parse(this.saveDialogTags))
                : this.actionController.saveVersion(this.projectName, this.saveDialogMessage, ProjectVersionTags.parse(this.saveDialogTags));
        if ("luma.status.save_started".equals(result) || "luma.status.amend_started".equals(result)) {
            this.saveDialogVisible = false;
            this.activeTagInput = null;
        }
        this.refresh(result);
    }

    private void skipUpdate(UpdateProjectNotice notice) {
        this.updateCheckService.dismissVersion(notice.version());
        this.refresh(this.statusKey);
    }

    private void downloadUpdate(UpdateProjectNotice notice) {
        try {
            Util.getPlatform().openUri(URI.create(notice.downloadUrl()));
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Failed to open Lumi update download URL {}", notice.downloadUrl(), exception);
        } finally {
            this.updateCheckService.snoozeVersion(notice.version());
            this.refresh(this.statusKey);
        }
    }

    private void createBranch(BranchCreationDialogState dialog) {
        if (!dialog.canCreate()) {
            return;
        }
        BranchCreationResult result = this.actionController.createAndSwitchVariant(
                this.projectName,
                this.branchName.trim(),
                dialog.baseVersion().id()
        );
        if (result.switched()) {
            this.selectedVariantId = result.variantId();
            this.pendingBranchBaseVersionId = "";
            this.branchName = "";
        }
        this.refresh(result.statusKey());
    }

    private void closeBranchDialog() {
        this.pendingBranchBaseVersionId = "";
        this.branchName = "";
        this.refresh("luma.status.project_ready");
    }

    private Optional<ProjectVersion> pendingRestoreVersion() {
        if (this.pendingRestoreVersionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ProjectUiSupport.versionFor(this.state.versions(), this.pendingRestoreVersionId));
    }

    private Optional<ProjectVariant> pendingRestoreVariant(ProjectVersion version) {
        if (version == null || this.pendingRestoreVariantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ProjectUiSupport.variantFor(this.state.variants(), this.pendingRestoreVariantId));
    }

    private void confirmPendingRestore() {
        Optional<ProjectVersion> version = this.pendingRestoreVersion();
        Optional<ProjectVariant> variant = version.flatMap(this::pendingRestoreVariant);
        if (version.isEmpty() || variant.isEmpty()) {
            this.clearPendingRestore();
            this.refresh("luma.status.operation_failed");
            return;
        }
        this.clearPendingRestore();
        this.executeRestore(variant.get(), version.get());
    }

    private void confirmPendingSelectedRestore(PartialRestoreMode mode) {
        Optional<ProjectVersion> version = this.pendingRestoreVersion();
        Optional<Bounds3i> bounds = this.selectedLumiBounds();
        if (version.isEmpty() || bounds.isEmpty()) {
            this.clearPendingRestore();
            this.refresh("luma.status.operation_failed");
            return;
        }
        this.clearPendingRestore();
        this.executeSelectedRestore(version.get(), mode, bounds.get());
    }

    private final class RestoreDialogActions implements RestoreConfirmationDialogView.Actions {

        @Override
        public void cancel() {
            clearPendingRestore();
            refresh("luma.status.project_ready");
        }

        @Override
        public void restoreWhole() {
            confirmPendingRestore();
        }

        @Override
        public void restoreSelectedArea() {
            confirmPendingSelectedRestore(PartialRestoreMode.SELECTED_AREA);
        }

        @Override
        public void restoreOutsideSelection() {
            confirmPendingSelectedRestore(PartialRestoreMode.OUTSIDE_SELECTED_AREA);
        }
    }

    private final class UpdateDialogActions implements UpdateNoticeDialogView.Actions {

        @Override
        public void skip() {
            updateNotice().ifPresent(ProjectScreen.this::skipUpdate);
        }

        @Override
        public void download() {
            updateNotice().ifPresent(ProjectScreen.this::downloadUpdate);
        }
    }

    private final class BranchDialogActions implements BranchCreationDialogView.Actions {

        @Override
        public void updateBranchName(String value) {
            branchName = value == null ? "" : value;
        }

        @Override
        public boolean canCreate() {
            return branchDialogState().canCreate();
        }

        @Override
        public void create() {
            createBranch(branchDialogState());
        }

        @Override
        public void cancel() {
            closeBranchDialog();
        }
    }

    private void executeRestore(ProjectVariant variant, ProjectVersion version) {
        if (variant == null || version == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        this.refresh(this.actionController.restoreVersion(this.projectName, version.id(), variant.id()));
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

        String activeVariantId = this.state.project().activeVariantId() == null
                ? ""
                : this.state.project().activeVariantId();
        boolean selectedMissing = this.selectedVariantId.isBlank()
                || ProjectUiSupport.variantFor(this.state.variants(), this.selectedVariantId) == null;
        boolean activeVariantChanged = !activeVariantId.equals(this.lastActiveVariantId);
        if (selectedMissing || activeVariantChanged && this.selectedVariantId.equals(this.lastActiveVariantId)) {
            this.selectedVariantId = activeVariantId;
        }
        this.lastActiveVariantId = activeVariantId;
    }

    private void refresh(String statusKey) {
        this.refresh(statusKey, true);
    }

    private void refresh(String statusKey, boolean preserveScroll) {
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.rebuildPreservingScroll(() -> this.bodyScroll, preserveScroll);
    }

    private boolean acceptTagCompletion() {
        List<String> knownTags = TagInputSupport.knownTags(this.state.versions());
        if (!this.tagEditorVersionId.isBlank() && TagInputSupport.hasSuggestion(this.tagEditorText, knownTags)) {
            this.tagEditorText = this.activeTagInput == null
                    ? TagInputSupport.acceptSuggestion(this.tagEditorText, knownTags, true)
                    : TagInputSupport.acceptInto(this.activeTagInput, this.tagEditorText, knownTags, true);
            return true;
        }
        if (TagInputSupport.hasSuggestion(this.historyTagFilter, knownTags)) {
            this.historyTagFilter = TagInputSupport.acceptSuggestion(this.historyTagFilter, knownTags, false);
            this.refresh("luma.status.project_ready");
            return true;
        }
        return false;
    }

    private boolean acceptSaveDialogTagCompletion() {
        List<String> knownTags = TagInputSupport.knownTags(this.state.versions());
        if (!TagInputSupport.hasSuggestion(this.saveDialogTags, knownTags)) {
            return false;
        }
        this.saveDialogTags = this.activeTagInput == null
                ? TagInputSupport.acceptSuggestion(this.saveDialogTags, knownTags, true)
                : TagInputSupport.acceptInto(this.activeTagInput, this.saveDialogTags, knownTags, true);
        return true;
    }

    @Override
    protected void onLumaTick() {
        if (this.onboardingTour != null && this.handleOnboardingTransition(this.onboardingTour.tick())) {
            return;
        }
        if (this.hasPendingCompareOverlay() && ++this.refreshCooldown >= 10) {
            this.refreshCooldown = 0;
            this.continuePendingCompareOverlay();
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

    private void requestCompareOverlay(String leftReference, String rightReference) {
        this.pendingCompareLeftReference = leftReference == null ? "" : leftReference;
        this.pendingCompareRightReference = rightReference == null ? "" : rightReference;
        this.continuePendingCompareOverlay();
    }

    private void continuePendingCompareOverlay() {
        CompareViewState compare = this.compareController.loadState(
                this.projectName,
                this.pendingCompareLeftReference,
                this.pendingCompareRightReference,
                "luma.status.compare_loading"
        );
        if (compare.loadState() == CompareLoadState.LOADING) {
            this.refresh(compare.status());
            return;
        }
        if (compare.loadState() == CompareLoadState.READY) {
            String result = this.compareController.showOverlay(this.projectName, compare);
            this.pendingCompareLeftReference = "";
            this.pendingCompareRightReference = "";
            if ("luma.status.compare_no_changes".equals(result) || "luma.status.compare_failed".equals(result)) {
                this.refresh(result);
                return;
            }
            this.statusKey = result;
            this.client.setScreen(null);
            return;
        }
        this.pendingCompareLeftReference = "";
        this.pendingCompareRightReference = "";
        this.refresh(compare.status());
    }

    private boolean hasPendingCompareOverlay() {
        return !this.pendingCompareRightReference.isBlank();
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
            case REBUILD -> this.refresh(this.statusKey);
            case CLOSE_WORKSPACE -> this.closeWorkspaceForWorldTeaching();
            case OPEN_CONTROLS -> this.openControls();
            case OPEN_WORKSPACE, EXECUTE_UNDO, EXECUTE_REDO -> {
            }
            case COMPLETE -> this.completeOnboarding();
            case NONE -> {
            }
        }
        return true;
    }

    private void openControls() {
        this.client.setScreen(new ControlsScreen(this, this.client.options));
    }

    private void completeOnboarding() {
        this.onboardingService.markCompleted();
        this.onboardingTour = null;
        this.refresh(this.statusKey);
    }

    private void closeWorkspaceForWorldTeaching() {
        ClientOnboardingFlowCoordinator.getInstance().startBreakBlockStep(
                this.projectName,
                this.selectedVariantId,
                this.statusKey,
                this.onboardingService,
                this.onboardingTour
        );
        this.client.setScreen(null);
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

    private final class SectionActions implements ProjectScreenSections.Actions {

        @Override
        public void openSave() {
            openSaveDialog("");
        }

        @Override
        public void openAmend(ProjectVersion activeHead) {
            if (activeHead == null) {
                return;
            }
            openSaveDialog(ProjectUiSupport.displayMessage(activeHead));
        }

        @Override
        public void openCompare(String leftReference, String rightReference, String contextVersionId) {
            requestCompareOverlay(leftReference, rightReference);
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
        public void openBranchDialog(ProjectVersion version) {
            pendingBranchBaseVersionId = version == null ? "" : version.id();
            branchName = "";
            refresh("luma.status.project_ready");
        }

        @Override
        public void setHistoryGraphVisible(boolean visible) {
            historyGraphVisible = visible;
            refresh("luma.status.project_ready");
        }

        @Override
        public void selectHistoryVariant(String variantId) {
            selectedVariantId = variantId == null ? "" : variantId;
            refresh("luma.status.project_ready");
        }

        @Override
        public void setHistoryTagFilter(String filter) {
            historyTagFilter = TagInputSupport.limit(filter);
        }

        @Override
        public void refreshHistoryView() {
            refresh("luma.status.project_ready");
        }

        @Override
        public void toggleTagEditor(ProjectVersion version) {
            if (version == null) {
                return;
            }
            if (version.id().equals(tagEditorVersionId)) {
                tagEditorVersionId = "";
                tagEditorText = "";
            } else {
                tagEditorVersionId = version.id();
                tagEditorText = TagInputSupport.limit(ProjectVersionTags.serialize(ProjectVersionTags.from(version)));
            }
            refresh("luma.status.project_ready");
        }

        @Override
        public void updateTagEditor(String value) {
            tagEditorText = TagInputSupport.limit(value);
        }

        @Override
        public void bindTagInput(TextBoxComponent input) {
            activeTagInput = input;
        }

        @Override
        public void saveTags(ProjectVersion version) {
            if (version == null) {
                return;
            }
            String result = actionController.updateVersionTags(projectName, version.id(), ProjectVersionTags.parse(tagEditorText));
            if ("luma.status.tags_updated".equals(result)) {
                tagEditorVersionId = "";
                tagEditorText = "";
            }
            refresh(result);
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

    }
}
