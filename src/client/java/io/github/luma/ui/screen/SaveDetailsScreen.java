package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.service.ProjectVersionVisibility;
import io.github.luma.ui.ContextualHelpPresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.controller.BranchCreationDialogStateFactory;
import io.github.luma.ui.controller.BranchCreationResult;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.screen.section.BranchCreationDialogView;
import io.github.luma.ui.screen.section.ConfirmationDialogView;
import io.github.luma.ui.screen.section.RestoreConfirmationDialogView;
import io.github.luma.ui.screen.section.SaveDetailsPartialRestoreSection;
import io.github.luma.ui.state.BranchCreationDialogState;
import io.github.luma.ui.state.PartialRestoreFormState;
import io.github.luma.ui.state.RestoreEntitySelectionState;
import io.github.luma.ui.state.SaveDetailsViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.StackLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class SaveDetailsScreen extends LumaScreen {

    private static final int[] PREVIEW_VIEWPORT_WIDTH_STEPS = {168, 212, 284};
    private static final int MAX_PREVIEW_ZOOM_STEP = 3;

    private final Screen parent;
    private final String projectName;
    private final String versionId;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ProjectVersionVisibility versionVisibility = new ProjectVersionVisibility();
    private final BranchCreationDialogStateFactory branchDialogFactory = new BranchCreationDialogStateFactory();
    private final BranchCreationDialogView branchDialogView = new BranchCreationDialogView(this.controller, new BranchDialogActions());
    private final RestoreConfirmationDialogView restoreDialogView = new RestoreConfirmationDialogView(new RestoreDialogActions());
    private final ConfirmationDialogView deleteDialogView = new ConfirmationDialogView(new DeleteDialogActions());
    private final ScreenRouter router = new ScreenRouter();
    private final PartialRestoreFormState partialRestoreForm = new PartialRestoreFormState();
    private final RestoreEntitySelectionState restoreEntitySelection = new RestoreEntitySelectionState();
    private final SaveDetailsPartialRestoreSection partialRestoreSections = new SaveDetailsPartialRestoreSection(new PartialRestoreActions());
    private final ClientContextualHelpService contextualHelpService = new ClientContextualHelpService();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private SaveDetailsViewState state = new SaveDetailsViewState(
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private boolean pendingRestoreConfirmation = false;
    private boolean pendingDeleteConfirmation = false;
    private boolean showPartialRestore = false;
    private String renameVersionId = "";
    private String renameMessage = "";
    private String tagVersionId = "";
    private String tagText = "";
    private boolean tagEditorVisible = false;
    private TextBoxComponent activeTagInput;
    private String pendingBranchBaseVersionId = "";
    private String branchName = "";
    private int previewZoomStep = 0;
    private int previewPanX = 0;
    private int previewPanY = 0;
    private int refreshCooldown = 0;

    public SaveDetailsScreen(Screen parent, String projectName, String versionId) {
        super(Component.translatable("luma.screen.save_details.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.versionId = versionId;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        ProjectVersion version = this.state.selectedVersion();
        ProjectVariant versionVariant = version == null ? null : ProjectUiSupport.variantFor(this.state.variants(), version.variantId());
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        StackLayout stack = UIContainers.stack(Sizing.fill(100), Sizing.fill(100));
        root.child(stack);

        FlowLayout frame = LumaUi.screenFrame();
        stack.child(this.animateOnFirstOpen(frame));

        if (version == null) {
            frame.child(LumaUi.closeHeader(Component.translatable("luma.screen.save_details.title", this.projectName), button -> this.onClose()));
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.save_details.empty_title"),
                    Component.translatable("luma.preview.no_version")
            ));
            return;
        }

        frame.child(LumaUi.closeHeader(Component.translatable(
                "luma.screen.save_details.title",
                ProjectUiSupport.displayMessage(version)
        ), button -> this.onClose()));
        if (this.shouldShowStatusBanner()) {
            frame.child(LumaUi.statusBanner(this.bannerText()));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        ContextualHelpPresenter contextualHelp = new ContextualHelpPresenter(this.contextualHelpService, this::rebuild);
        contextualHelp.addHint(body, ClientContextualHelpHint.RESTORE);
        body.child(this.summarySection(version, versionVariant));
        body.child(this.primaryActions(version, versionVariant, operationActive));
        body.child(this.changeStats(version));
        body.child(this.advancedInfoSection(version));
        body.child(this.moreSection(version, operationActive));
        if (this.showPartialRestore) {
            contextualHelp.addHint(body, ClientContextualHelpHint.PARTIAL_RESTORE);
            body.child(this.partialRestoreSection(version, operationActive));
        }
        body.child(LumaUi.bottomSpacer());

        BranchCreationDialogState branchDialog = this.branchDialogState();
        if (branchDialog.visible()) {
            stack.child(this.branchDialogView.overlay(new BranchCreationDialogView.Model(
                    this.projectName,
                    this.width,
                    branchDialog,
                    this.shouldShowStatusBanner() ? this.bannerText() : null
            )));
        } else if (this.pendingRestoreConfirmation && version != null && versionVariant != null) {
            stack.child(this.restoreDialogView.overlay(this.restoreDialogModel(version, versionVariant, operationActive)));
        } else if (this.pendingDeleteConfirmation && version != null) {
            stack.child(this.deleteDialogView.overlay(this.deleteDialogModel(version, operationActive)));
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.branchDialogState().visible() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.closeBranchDialog();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && this.acceptTagCompletion()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;

        SaveDetailsViewState refreshed = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        String normalizedStatusKey = ScreenOperationStateSupport.normalizeStatusKey(
                this.status,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatusKey.equals(this.status)) {
            this.status = normalizedStatusKey;
            refreshed = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.rebuild();
        }
    }

    private FlowLayout summarySection(ProjectVersion version, ProjectVariant versionVariant) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.summary_title"),
                Component.translatable(
                        "luma.save_details.summary_help",
                        ProjectUiSupport.formatTimestamp(version.createdAt())
                )
        );

        FlowLayout hero = this.width < 860
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        hero.gap(10);
        hero.child(this.previewPanel(version));

        FlowLayout text = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        text.gap(6);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));

        if (ProjectUiSupport.isVariantHead(this.state.variants(), version)) {
            FlowLayout meta = LumaUi.actionRow();
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
            text.child(meta);
        }
        text.child(LumaUi.caption(Component.translatable("luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        text.child(this.tagsSection(version));
        hero.child(text);
        section.child(hero);
        return section;
    }

    private FlowLayout tagsSection(ProjectVersion version) {
        this.ensureTagText(version);
        FlowLayout section = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        section.gap(4);

        FlowLayout row = LumaUi.actionRow();
        List<String> tags = ProjectVersionTags.from(version);
        if (tags.isEmpty()) {
            row.child(LumaUi.caption(Component.translatable("luma.history.tags_empty")));
        } else {
            for (String tag : tags) {
                row.child(LumaUi.chip(Component.literal("#" + tag)));
            }
        }
        row.child(LumaUi.iconButton("tags", Component.translatable("luma.action.edit_tags"), button -> {
            this.tagEditorVisible = !this.tagEditorVisible;
            this.rebuild();
        }));
        section.child(row);

        if (this.tagEditorVisible) {
            FlowLayout editor = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
            editor.gap(2);
            FlowLayout editRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
            editRow.gap(4);
            editRow.verticalAlignment(VerticalAlignment.CENTER);
            TextBoxComponent input = UIComponents.textBox(Sizing.expand(100), this.tagText);
            input.setHint(Component.translatable("luma.history.tags_input"));
            TagInputSupport.configure(input, this.tagText, TagInputSupport.knownTags(this.state.versions()), true);
            TagSuggestionComponent suggestions = new TagSuggestionComponent(
                    () -> this.tagText,
                    () -> TagInputSupport.knownTags(this.state.versions()),
                    true,
                    accepted -> {
                        this.tagText = accepted;
                        input.setValue(accepted);
                        input.setCursorPosition(accepted.length());
                    }
            );
            input.onChanged().subscribe(value -> {
                this.tagText = TagInputSupport.limit(value);
                suggestions.refresh();
            });
            this.activeTagInput = input;
            editRow.child(input);
            ButtonComponent save = LumaUi.iconButton("save", Component.translatable("luma.action.save_tags"), button -> this.saveTags(version));
            save.margins(Insets.none());
            editRow.child(save);
            editor.child(editRow);
            editor.child(suggestions);
            section.child(editor);
        }
        return section;
    }

    private void ensureTagText(ProjectVersion version) {
        if (version == null) {
            return;
        }
        if (!version.id().equals(this.tagVersionId)) {
            this.tagVersionId = version.id();
            this.tagText = TagInputSupport.limit(ProjectVersionTags.serialize(ProjectVersionTags.from(version)));
            this.tagEditorVisible = false;
        }
    }

    private FlowLayout previewPanel(ProjectVersion version) {
        int previewWidth = this.previewViewportWidth();
        int previewHeight = Math.max(96, (previewWidth * 3) / 4);
        FlowLayout panel = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        panel.gap(4);
        panel.child(ProjectUiSupport.zoomableVersionPreview(
                this.controller,
                this.projectName,
                version,
                previewWidth,
                previewHeight,
                this.previewZoomStep,
                this.previewPanX,
                this.previewPanY,
                (x, y) -> {
                    this.previewPanX = x;
                    this.previewPanY = y;
                }
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent zoomOut = LumaUi.iconButton("minus", Component.translatable("luma.action.zoom_out"), button -> {
            this.previewZoomStep = Math.max(0, this.previewZoomStep - 1);
            this.rebuild();
        });
        zoomOut.active(this.previewZoomStep > 0);
        actions.child(zoomOut);

        ButtonComponent zoomIn = LumaUi.iconButton("plus", Component.translatable("luma.action.zoom_in"), button -> {
            this.previewZoomStep = Math.min(MAX_PREVIEW_ZOOM_STEP, this.previewZoomStep + 1);
            this.rebuild();
        });
        zoomIn.active(this.previewZoomStep < MAX_PREVIEW_ZOOM_STEP);
        actions.child(zoomIn);
        ButtonComponent left = LumaUi.iconButton("chevron-left", Component.translatable("luma.action.back"), button -> this.panPreview(-24, 0));
        left.active(this.previewZoomStep > 0);
        actions.child(left);
        ButtonComponent up = LumaUi.iconButton("chevron-up", Component.translatable("luma.action.preview_pan_up"), button -> this.panPreview(0, -24));
        up.active(this.previewZoomStep > 0);
        actions.child(up);
        ButtonComponent down = LumaUi.iconButton("chevron-down", Component.translatable("luma.action.preview_pan_down"), button -> this.panPreview(0, 24));
        down.active(this.previewZoomStep > 0);
        actions.child(down);
        ButtonComponent right = LumaUi.iconButton("chevron-right", Component.translatable("luma.action.next"), button -> this.panPreview(24, 0));
        right.active(this.previewZoomStep > 0);
        actions.child(right);
        panel.child(actions);
        return panel;
    }

    private void panPreview(int deltaX, int deltaY) {
        this.previewPanX += deltaX;
        this.previewPanY += deltaY;
        this.rebuild();
    }

    private int previewViewportWidth() {
        return PREVIEW_VIEWPORT_WIDTH_STEPS[this.previewViewportStep()];
    }

    private int previewViewportStep() {
        if (this.width < 560) {
            return 0;
        }
        if (this.width < 760) {
            return 1;
        }
        return PREVIEW_VIEWPORT_WIDTH_STEPS.length - 1;
    }

    private FlowLayout changeStats(ProjectVersion version) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.changes_title"),
                Component.translatable("luma.save_details.changes_help")
        );
        FlowLayout stats = LumaUi.actionRow();
        ChangeStats changeStats = version.stats() == null ? ChangeStats.empty() : version.stats();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_blocks"),
                Component.literal(Integer.toString(changeStats.changedBlocks()))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_chunks"),
                Component.literal(Integer.toString(changeStats.changedChunks()))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_types"),
                Component.literal(Integer.toString(changeStats.distinctBlockTypes()))
        ));
        section.child(stats);
        return section;
    }

    private FlowLayout primaryActions(ProjectVersion version, ProjectVariant versionVariant, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.actions_title"),
                null
        );

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent restoreButton = LumaUi.primaryButton(Component.translatable("luma.action.restore_this_save"), button -> this.restoreVersion(version, versionVariant));
        restoreButton.active(!operationActive);
        actions.child(restoreButton);

        ButtonComponent branchButton = LumaUi.iconButton(
                "branch",
                Component.translatable("luma.save_details.create_idea"),
                button -> this.openBranchDialog(version)
        );
        branchButton.active(!operationActive);
        actions.child(branchButton);

        section.child(actions);
        return section;
    }

    private FlowLayout partialRestoreSection(ProjectVersion version, boolean operationActive) {
        return this.partialRestoreSections.section(new SaveDetailsPartialRestoreSection.Model(
                this.projectName,
                version,
                this.client.getUser().getName(),
                operationActive,
                this.partialRestoreForm,
                this.state.project() == null ? null : this.state.project().bounds(),
                this.fallbackPartialRestoreBounds(),
                this.selectedLumiBounds(),
                this.restoreMetadata(version)
        ));
    }

    private FlowLayout moreSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.more_title"),
                null
        );

        FlowLayout expanded = LumaUi.revealGroup();
        this.ensureRenameMessage(version);
        expanded.child(this.renameSection(version, operationActive));
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent replaceButton = LumaUi.button(Component.translatable("luma.action.amend_version"), button ->
                this.refresh(this.controller.amendVersion(
                        this.projectName,
                        ProjectUiSupport.displayMessage(version),
                        ProjectVersionTags.from(version)
                )));
        replaceButton.active(this.canReplaceLatest(version) && !operationActive);
        actions.child(replaceButton);

        actions.child(LumaUi.button(Component.translatable("luma.action.restore_selected_area"), button -> {
            this.showPartialRestore = !this.showPartialRestore;
            this.rebuild();
        }));

        ButtonComponent deleteButton = LumaUi.iconButton("trash", Component.translatable("luma.action.delete_save"), button -> {
            this.pendingDeleteConfirmation = true;
            this.refresh("luma.status.version_delete_confirm");
        });
        deleteButton.active(this.canDeleteVersion(version) && !operationActive);
        actions.child(deleteButton);

        expanded.child(actions);
        section.child(expanded);
        return section;
    }

    private FlowLayout renameSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.save_details.rename_title"),
                Component.translatable("luma.save_details.rename_help")
        );
        ButtonComponent renameButton = LumaUi.primaryButton(Component.translatable("luma.action.rename_save"), button -> {
            String result = this.controller.renameVersion(this.projectName, version.id(), this.renameMessage);
            if ("luma.status.version_renamed".equals(result)) {
                this.renameVersionId = "";
            }
            this.refresh(result);
        });
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.renameMessage);
        input.setHint(Component.translatable("luma.save.name_input"));
        input.onChanged().subscribe(value -> {
            this.renameMessage = value == null ? "" : value;
            renameButton.active(this.canRenameVersion(version, operationActive));
        });
        section.child(input);

        FlowLayout actions = LumaUi.actionRow();
        renameButton.active(this.canRenameVersion(version, operationActive));
        actions.child(renameButton);
        section.child(actions);
        return section;
    }

    private void ensureRenameMessage(ProjectVersion version) {
        if (version == null) {
            return;
        }
        if (!version.id().equals(this.renameVersionId)) {
            this.renameVersionId = version.id();
            this.renameMessage = ProjectUiSupport.displayMessage(version);
        }
    }

    private void saveTags(ProjectVersion version) {
        if (version == null) {
            return;
        }
        String result = this.controller.updateVersionTags(
                this.projectName,
                version.id(),
                ProjectVersionTags.parse(this.tagText)
        );
        if ("luma.status.tags_updated".equals(result)) {
            this.tagVersionId = "";
            this.tagEditorVisible = false;
        }
        this.refresh(result);
    }

    private boolean acceptTagCompletion() {
        if (!this.tagEditorVisible) {
            return false;
        }
        List<String> knownTags = TagInputSupport.knownTags(this.state.versions());
        if (!TagInputSupport.hasSuggestion(this.tagText, knownTags)) {
            return false;
        }
        this.tagText = this.activeTagInput == null
                ? TagInputSupport.acceptSuggestion(this.tagText, knownTags, true)
                : TagInputSupport.acceptInto(this.activeTagInput, this.tagText, knownTags, true);
        return true;
    }

    private BranchCreationDialogState branchDialogState() {
        return this.branchDialogFactory.create(
                this.state.versions(),
                this.state.variants(),
                this.state.operationSnapshot(),
                this.pendingBranchBaseVersionId,
                this.branchName
        );
    }

    private void openBranchDialog(ProjectVersion version) {
        this.pendingBranchBaseVersionId = version == null ? "" : version.id();
        this.branchName = "";
        this.refresh("luma.status.project_ready");
    }

    private void createBranch(BranchCreationDialogState dialog) {
        if (!dialog.canCreate()) {
            return;
        }
        BranchCreationResult result = this.controller.createAndSwitchVariant(
                this.projectName,
                this.branchName.trim(),
                dialog.baseVersion().id()
        );
        if (result.switched()) {
            this.pendingBranchBaseVersionId = "";
            this.branchName = "";
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result.variantId(), result.statusKey());
            return;
        }
        this.refresh(result.statusKey());
    }

    private void closeBranchDialog() {
        this.pendingBranchBaseVersionId = "";
        this.branchName = "";
        this.refresh("luma.status.project_ready");
    }

    private FlowLayout advancedInfoSection(ProjectVersion version) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.advanced_info_title"),
                Component.translatable("luma.save_details.advanced_info_help")
        );
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_id", version.id())));
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_author", ProjectUiSupport.safeText(version.author()))));
        section.child(LumaUi.caption(Component.translatable(
                "luma.save_details.raw_info_type",
                Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))
        )));
        return section;
    }

    private RestoreConfirmationDialogView.Model restoreDialogModel(
            ProjectVersion version,
            ProjectVariant versionVariant,
            boolean operationActive
    ) {
        boolean zoneScoped = !this.versionVisibility.workZoneId(version).isBlank();
        return new RestoreConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.restore.confirm_title", ProjectUiSupport.displayMessage(version)),
                Component.translatable(zoneScoped ? "luma.restore.confirm_zone_help" : "luma.restore.confirm_help"),
                Component.translatable(
                        "luma.restore.confirm_target",
                        ProjectUiSupport.displayVariantName(versionVariant),
                        ProjectUiSupport.displayMessage(version)
                ),
                !zoneScoped && this.state.project().settings().safetySnapshotBeforeRestore(),
                version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT,
                !zoneScoped && this.selectedLumiBounds().isPresent(),
                operationActive,
                this.restoreEntitySelection.expanded(),
                this.restoreEntitySelection.options(zoneScoped
                        ? this.controller.restoreEntityTypes(this.zoneRestoreRequest(
                                version,
                                this.versionVisibility.workZoneId(version),
                                RestoreEntityTypeSelection.includeAll()
                        ))
                        : this.controller.restoreEntityTypes(this.projectName, version.id()))
        );
    }

    private ConfirmationDialogView.Model deleteDialogModel(ProjectVersion version, boolean operationActive) {
        return new ConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.save_details.delete_title"),
                Component.translatable("luma.save_details.delete_help"),
                Component.translatable("luma.save_details.delete_warning"),
                Component.translatable("luma.action.delete_save"),
                !this.canDeleteVersion(version) || operationActive
        );
    }

    private void restoreVersion(ProjectVersion version, ProjectVariant versionVariant) {
        if (version == null || versionVariant == null) {
            return;
        }

        this.pendingRestoreConfirmation = true;
        this.restoreEntitySelection.reset();
        this.refresh("luma.status.restore_confirmation_required");
    }

    private void executeRestore(
            ProjectVersion version,
            ProjectVariant versionVariant,
            RestoreEntityTypeSelection selection
    ) {
        if (version == null || versionVariant == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        String zoneId = this.versionVisibility.workZoneId(version);
        if (!zoneId.isBlank()) {
            this.executeZoneRestore(version, zoneId, selection);
            return;
        }

        String result = this.controller.restoreVersion(this.projectName, version.id(), "", selection);
        this.router.openProjectIgnoringRecovery(this.parent, this.projectName, version.variantId(), result);
    }

    private void executeZoneRestore(ProjectVersion version, String zoneId, RestoreEntityTypeSelection selection) {
        String result = this.controller.partialRestore(this.zoneRestoreRequest(version, zoneId, selection));
        this.router.openProjectIgnoringRecovery(this.parent, this.projectName, version.variantId(), result);
    }

    private PartialRestoreRequest zoneRestoreRequest(
            ProjectVersion version,
            String zoneId,
            RestoreEntityTypeSelection selection
    ) {
        return new PartialRestoreRequest(
                this.projectName,
                version.id(),
                null,
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.LUMI_REGION,
                selection,
                this.client.getUser().getName(),
                Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId)
        );
    }

    private void executeSelectedRestore(
            ProjectVersion version,
            PartialRestoreMode mode,
            Bounds3i bounds,
            RestoreEntityTypeSelection selection
    ) {
        if (version == null || bounds == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }
        this.pendingRestoreConfirmation = false;
        PartialRestoreRequest request = new PartialRestoreRequest(
                this.projectName,
                version.id(),
                bounds,
                mode,
                PartialRestoreRegionSource.LUMI_REGION,
                selection,
                this.client.getUser().getName(),
                this.restoreMetadata(version)
        );
        String result = this.controller.partialRestore(request);
        this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
    }

    private Map<String, String> restoreMetadata(ProjectVersion version) {
        String zoneId = this.versionVisibility.workZoneId(version);
        return zoneId.isBlank() ? Map.of() : Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId);
    }

    private boolean canReplaceLatest(ProjectVersion version) {
        return version != null
                && this.state.project() != null
                && version.variantId().equals(this.state.project().activeVariantId())
                && ProjectUiSupport.isVariantHead(this.state.variants(), version)
                && this.state.recoveryDraft() != null
                && !this.state.recoveryDraft().isEmpty();
    }

    private boolean canDeleteVersion(ProjectVersion version) {
        if (version == null
                || version.versionKind() == VersionKind.INITIAL
                || version.versionKind() == VersionKind.WORLD_ROOT
                || version.parentVersionId() == null
                || version.parentVersionId().isBlank()) {
            return false;
        }
        return true;
    }

    private boolean canRenameVersion(ProjectVersion version, boolean operationActive) {
        return version != null
                && !operationActive
                && !this.renameMessage.trim().isBlank()
                && !this.renameMessage.trim().equals(ProjectUiSupport.displayMessage(version));
    }

    private Bounds3i fallbackPartialRestoreBounds() {
        BlockPos pos = this.client.player == null ? BlockPos.ZERO : this.client.player.blockPosition();
        int minY = this.client.level == null ? -64 : this.client.level.getMinY();
        int maxY = this.client.level == null ? 320 : this.client.level.getMaxY();
        return PartialRestoreFormState.fallbackAround(pos, minY, maxY);
    }

    private java.util.Optional<Bounds3i> selectedLumiBounds() {
        if (this.state.project() == null) {
            return java.util.Optional.empty();
        }
        return LumiRegionSelectionController.getInstance().selectedBounds(
                this.projectName,
                this.state.project().dimensionId()
        );
    }

    private boolean shouldShowStatusBanner() {
        return ScreenOperationStateSupport.shouldShowStatusBanner(
                this.state.status(),
                this.state.operationSnapshot(),
                "luma.status.project_ready"
        );
    }

    private Component bannerText() {
        return ScreenOperationStateSupport.bannerText(
                this.state.status(),
                this.state.operationSnapshot(),
                "luma.status.project_ready"
        );
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        this.rebuildPreservingScroll(() -> this.bodyScroll);
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

    private final class RestoreDialogActions implements RestoreConfirmationDialogView.Actions {

        @Override
        public void cancel() {
            pendingRestoreConfirmation = false;
            restoreEntitySelection.reset();
            rebuild();
        }

        @Override
        public void restoreWhole() {
            ProjectVersion version = state.selectedVersion();
            ProjectVariant versionVariant = version == null
                    ? null
                    : ProjectUiSupport.variantFor(state.variants(), version.variantId());
            RestoreEntityTypeSelection selection = restoreEntitySelection.selection();
            pendingRestoreConfirmation = false;
            restoreEntitySelection.reset();
            executeRestore(version, versionVariant, selection);
        }

        @Override
        public void restoreSelectedArea() {
            RestoreEntityTypeSelection selection = restoreEntitySelection.selection();
            pendingRestoreConfirmation = false;
            restoreEntitySelection.reset();
            selectedLumiBounds().ifPresentOrElse(
                    bounds -> executeSelectedRestore(state.selectedVersion(), PartialRestoreMode.SELECTED_AREA, bounds, selection),
                    () -> refresh("luma.status.operation_failed")
            );
        }

        @Override
        public void restoreOutsideSelection() {
            RestoreEntityTypeSelection selection = restoreEntitySelection.selection();
            pendingRestoreConfirmation = false;
            restoreEntitySelection.reset();
            selectedLumiBounds().ifPresentOrElse(
                    bounds -> executeSelectedRestore(state.selectedVersion(), PartialRestoreMode.OUTSIDE_SELECTED_AREA, bounds, selection),
                    () -> refresh("luma.status.operation_failed")
            );
        }

        @Override
        public void toggleEntityList() {
            restoreEntitySelection.toggleExpanded();
            refresh("luma.status.restore_confirmation_required");
        }

        @Override
        public void toggleEntityType(String entityType) {
            restoreEntitySelection.toggleEntityType(entityType);
            refresh("luma.status.restore_confirmation_required");
        }
    }

    private final class DeleteDialogActions implements ConfirmationDialogView.Actions {

        @Override
        public void confirm() {
            ProjectVersion version = state.selectedVersion();
            if (version == null) {
                pendingDeleteConfirmation = false;
                refresh("luma.status.operation_failed");
                return;
            }
            pendingDeleteConfirmation = false;
            String result = controller.deleteVersion(projectName, version.id());
            if ("luma.status.version_deleted".equals(result)) {
                router.openProjectIgnoringRecovery(parent, projectName, result);
                return;
            }
            refresh(result);
        }

        @Override
        public void cancel() {
            pendingDeleteConfirmation = false;
            refresh("luma.status.project_ready");
        }
    }

    private final class PartialRestoreActions implements SaveDetailsPartialRestoreSection.Actions {

        @Override
        public void preview(PartialRestoreRequest request) {
            partialRestoreForm.setSummary(controller.partialRestorePlanSummary(request));
            refresh(ProjectScreenController.partialRestorePreviewStatus(partialRestoreForm.summary()));
        }

        @Override
        public void apply(PartialRestoreRequest request) {
            String result = controller.partialRestore(request);
            router.openProjectIgnoringRecovery(parent, projectName, result);
        }

        @Override
        public void selectionApplied() {
            refresh("luma.status.partial_restore_selection_applied");
        }

        @Override
        public void modeChanged() {
            refresh("luma.status.partial_restore_mode_changed");
        }

        @Override
        public void invalidBounds() {
            refresh("luma.status.partial_restore_invalid_bounds");
        }
    }
}
