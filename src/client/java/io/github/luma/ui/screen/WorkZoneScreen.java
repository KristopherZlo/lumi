package io.github.luma.ui.screen;

import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.service.ProjectVersionVisibility;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.controller.BranchCreationDialogStateFactory;
import io.github.luma.ui.controller.BranchCreationResult;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.WorkZoneScreenController;
import io.github.luma.ui.graph.CommitGraphComponent;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.overlay.WorkZoneOverlayRenderer;
import io.github.luma.ui.screen.section.BranchCreationDialogView;
import io.github.luma.ui.screen.section.ProjectSaveCardView;
import io.github.luma.ui.screen.section.RestoreConfirmationDialogView;
import io.github.luma.ui.state.BranchCreationDialogState;
import io.github.luma.ui.state.WorkZoneViewState;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class WorkZoneScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final WorkZoneScreenController controller = new WorkZoneScreenController();
    private final ProjectScreenController projectController = new ProjectScreenController();
    private final BranchCreationDialogStateFactory branchDialogFactory = new BranchCreationDialogStateFactory();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ProjectVersionVisibility versionVisibility = new ProjectVersionVisibility();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSaveCardView saveCardView = new ProjectSaveCardView(this.projectController, new ZoneSaveCardActions());
    private final BranchCreationDialogView branchDialogView = new BranchCreationDialogView(this.projectController, new BranchDialogActions());
    private final RestoreConfirmationDialogView restoreDialogView = new RestoreConfirmationDialogView(new RestoreDialogActions());
    private WorkZoneViewState state;
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private String status = "luma.status.zones_ready";
    private String newZoneName = "";
    private String saveMessage = "";
    private String saveTags = "";
    private boolean saveDialogVisible;
    private String pendingSaveZoneId = "";
    private String pendingRestoreVariantId = "";
    private String pendingRestoreVersionId = "";
    private String pendingBranchBaseVersionId = "";
    private String branchName = "";
    private boolean zonePickerVisible;
    private boolean zoneHistoryGraphVisible;
    private String openedZoneId = "";
    private String selectedZoneVariantId = "";
    private String zoneTagFilter = "";
    private String tagEditorVersionId = "";
    private String tagEditorText = "";
    private TextBoxComponent activeTagInput;
    private TextBoxComponent saveTagsInput;

    public WorkZoneScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.zones.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.load(this.projectName, this.status);
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        if (this.state.project() == null) {
            FlowLayout frame = LumaUi.screenFrame();
            root.child(frame);
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable(this.state.status())
            ));
            return;
        }

        StackLayout stack = UIContainers.stack(Sizing.fill(100), Sizing.fill(100));
        root.child(stack);

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.zones.title", this.effectiveProjectName()),
                this.state.project(),
                this.state.variants()
        );
        stack.child(window.root());
        this.sidebarNavigation.attach(window, this, this.effectiveProjectName(), ProjectWorkspaceTab.ZONES, this.activeZoneColor());
        WorkZone focused = this.focusedZone();
        if (focused != null && !this.zonePickerVisible) {
            window.titleBar().child(LumaUi.iconButton("arrow-left", Component.translatable("luma.action.back"), button -> {
                this.zonePickerVisible = true;
                this.openedZoneId = "";
                this.rebuild();
            }));
        }
        if (!"luma.status.zones_ready".equals(this.state.status())) {
            window.content().child(LumaUi.statusBanner(Component.translatable(this.state.status())));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);
        if (focused != null && !this.zonePickerVisible) {
            boolean active = focused.id().equals(this.state.zones().activeZoneId(this.state.actor()));
            body.child(this.zoneDetailSection(focused, active));
            body.child(this.saveZoneSection(focused, active));
            body.child(this.zoneHistorySection(focused));
            body.child(LumaUi.bottomSpacer());
        } else {
            body.child(this.createZoneSection());
            body.child(this.zoneListSection());
            body.child(LumaUi.bottomSpacer());
        }

        BranchCreationDialogState branchDialog = this.branchDialogState();
        if (branchDialog.visible()) {
            stack.child(this.branchDialogView.overlay(new BranchCreationDialogView.Model(
                    this.effectiveProjectName(),
                    this.width,
                    branchDialog,
                    null
            )));
        } else if (!this.pendingRestoreVersionId.isBlank()) {
            RestoreConfirmationDialogView.Model dialog = this.restoreDialogModel();
            if (dialog != null) {
                stack.child(this.restoreDialogView.overlay(dialog));
            }
        } else if (this.saveDialogVisible) {
            stack.child(this.zoneSaveDialogOverlay());
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    public void refreshFromRemote(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.zones_ready" : statusKey;
        this.rebuild();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.saveDialogVisible && OnboardingScreen.isEscapeKey(event)) {
            this.closeZoneSaveDialog();
            return true;
        }
        if (this.saveDialogVisible && event.key() == GLFW.GLFW_KEY_TAB && this.acceptSaveDialogTagCompletion()) {
            return true;
        }
        if (this.branchDialogState().visible() && OnboardingScreen.isEscapeKey(event)) {
            this.closeBranchDialog();
            return true;
        }
        if (!this.pendingRestoreVersionId.isBlank() && OnboardingScreen.isEscapeKey(event)) {
            this.clearPendingRestore();
            this.refresh("luma.status.zones_ready");
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && this.acceptTagCompletion()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    private FlowLayout createZoneSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.create_title"),
                Component.translatable("luma.zones.create_help")
        );
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.newZoneName);
        input.onChanged().subscribe(value -> this.newZoneName = value);
        section.child(input);
        section.child(LumaUi.button(Component.translatable("luma.zones.create_button"), button -> {
            this.status = this.controller.createZone(this.effectiveProjectName(), this.newZoneName);
            if ("luma.status.zone_created".equals(this.status)) {
                this.newZoneName = "";
                this.zonePickerVisible = false;
            }
            this.rebuild();
        }));
        return section;
    }

    private FlowLayout zoneListSection() {
        FlowLayout section = LumaUi.panel(Sizing.fill(100), Sizing.content());
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(4);
        header.verticalAlignment(VerticalAlignment.CENTER);
        header.child(LumaUi.value(Component.translatable("luma.zones.list_title")));
        header.child(UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1)));
        header.child(LumaUi.button(
                Component.translatable(WorkZoneOverlayRenderer.showAllZones()
                        ? "luma.zones.render_focused"
                        : "luma.zones.render_all"),
                button -> {
                    WorkZoneOverlayRenderer.toggleShowAllZones();
                    this.rebuild();
                }
        ));
        section.child(header);
        section.child(LumaUi.caption(Component.translatable("luma.zones.list_help")));
        if (this.state.zones().zones().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.zones.empty")));
            return section;
        }
        String activeZoneId = this.state.zones().activeZoneId(this.state.actor());
        for (WorkZone zone : this.state.zones().zones()) {
            section.child(this.zoneCard(zone, zone.id().equals(activeZoneId)));
        }
        return section;
    }

    private FlowLayout zoneCard(WorkZone zone, boolean active) {
        FlowLayout card = active
                ? LumaUi.activeInsetPanel(Sizing.fill(100), Sizing.content())
                : LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(4);
        header.verticalAlignment(VerticalAlignment.CENTER);
        FlowLayout copy = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        FlowLayout title = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        title.gap(4);
        title.verticalAlignment(VerticalAlignment.CENTER);
        title.child(this.zoneColorDot(zone));
        title.child(LumaUi.value(Component.literal(zone.name())));
        copy.child(title);
        copy.child(LumaUi.caption(Component.translatable(
                "luma.zones.zone_meta",
                colorHex(zone.color()),
                zone.cells().size()
        )));
        int sharedCells = this.sharedCellCount(zone);
        if (sharedCells > 0) {
            copy.child(LumaUi.caption(Component.translatable("luma.zones.shared_cells", sharedCells)));
        }
        header.child(copy);
        FlowLayout actions = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        actions.gap(4);
        actions.verticalAlignment(VerticalAlignment.CENTER);
        ButtonComponent enterOrLeave = LumaUi.iconButton(
                active ? "log-out" : "log-in",
                Component.translatable(active ? "luma.zones.leave" : "luma.zones.enter"),
                button -> {
                    if (active) {
                        this.leaveZone(zone.id());
                    } else {
                        this.enterZone(zone.id());
                    }
                }
        );
        actions.child(enterOrLeave);
        actions.child(LumaUi.iconButton("folder-open", Component.translatable("luma.action.open_details"), button -> this.openZone(zone.id())));
        header.child(actions);
        card.child(header);
        if (zone.cells().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.zones.zone_draft")));
        }
        return card;
    }

    private FlowLayout zoneDetailSection(WorkZone zone, boolean active) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.details_title", zone.name()),
                Component.translatable(active ? "luma.zones.details_active" : "luma.zones.details_inactive")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.zones.zone_meta",
                colorHex(zone.color()),
                zone.cells().size()
        )));
        if (zone.cells().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.zones.zone_draft")));
        }
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent enter = LumaUi.iconButton(
                active ? "log-out" : "log-in",
                Component.translatable(active ? "luma.zones.leave" : "luma.zones.enter"),
                button -> {
                    if (active) {
                        this.leaveZone(zone.id());
                    } else {
                        this.enterZone(zone.id());
                    }
                }
        );
        actions.child(enter);
        section.child(actions);
        return section;
    }

    private FlowLayout saveZoneSection(WorkZone zone, boolean active) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.save_title"),
                Component.translatable(active ? "luma.zones.save_help" : "luma.zones.save_enter_first")
        );
        ButtonComponent save = LumaUi.primaryButton(Component.translatable("luma.zones.save_button"), button ->
                this.openZoneSaveDialog(zone.id()));
        save.active(active);
        section.child(save);
        return section;
    }

    private FlowLayout zoneSaveDialogOverlay() {
        FlowLayout overlay = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100));
        overlay.surface(Surface.flat(0x99000000));
        overlay.padding(Insets.of(10));
        overlay.horizontalAlignment(HorizontalAlignment.CENTER);
        overlay.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout modal = LumaUi.modalFrame(Math.min(360, Math.max(260, this.width - 40)));
        modal.child(LumaUi.closeHeader(Component.translatable("luma.zones.save_title"), button -> this.closeZoneSaveDialog()));
        if (!"luma.status.zones_ready".equals(this.state.status())) {
            modal.child(LumaUi.statusBanner(Component.translatable(this.state.status())));
        }
        modal.child(LumaUi.caption(Component.translatable("luma.save.summary_help")));

        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        input.setHint(Component.translatable("luma.save.name_help"));
        input.onChanged().subscribe(value -> this.saveMessage = value == null ? "" : value);
        modal.child(LumaUi.formField(
                Component.translatable("luma.save.name_input"),
                null,
                input
        ));

        TextBoxComponent tags = UIComponents.textBox(Sizing.fill(100), this.saveTags);
        this.saveTagsInput = tags;
        tags.setHint(Component.translatable("luma.history.tags_input"));
        TagInputSupport.configure(tags, this.saveTags, TagInputSupport.knownTags(this.state.versions()), true);
        FlowLayout tagInput = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        tagInput.gap(2);
        TagSuggestionComponent tagSuggestions = new TagSuggestionComponent(
                () -> this.saveTags,
                () -> TagInputSupport.knownTags(this.state.versions()),
                true,
                accepted -> {
                    this.saveTags = accepted;
                    tags.setValue(accepted);
                    tags.setCursorPosition(accepted.length());
                }
        );
        tags.onChanged().subscribe(value -> {
            this.saveTags = TagInputSupport.limit(value);
            tagSuggestions.refresh();
        });
        tagInput.child(tags);
        tagInput.child(tagSuggestions);
        modal.child(LumaUi.formField(
                Component.translatable("luma.save.tags_title"),
                null,
                tagInput
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent save = LumaUi.primaryButton(
                Component.translatable("luma.zones.save_button"),
                button -> this.saveZone(this.pendingSaveZoneId, ProjectVersionTags.parse(this.saveTags))
        );
        save.active(!this.pendingSaveZoneId.isBlank());
        actions.child(save);
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.closeZoneSaveDialog()));
        modal.child(actions);

        overlay.child(modal);
        return overlay;
    }

    private FlowLayout zoneHistorySection(WorkZone zone) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.history_title"),
                Component.translatable("luma.zones.history_help")
        );
        List<ProjectVersion> allVersions = this.zoneVersions(zone);
        section.child(this.zoneHistoryToolbar());
        if (allVersions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.zones.history_empty")));
            return section;
        }
        section.child(this.zoneBranchStrip(allVersions));
        String selectedVariantId = this.selectedZoneVariantId(allVersions);
        List<ProjectVersion> versions = allVersions.stream()
                .filter(version -> selectedVariantId.isBlank() || selectedVariantId.equals(version.variantId()))
                .filter(version -> this.matchesTagFilter(version, this.zoneTagFilter))
                .toList();
        if (versions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.history.tag_filter_empty")));
            return section;
        }
        if (this.zoneHistoryGraphVisible) {
            List<ProjectVersion> graphVersions = allVersions.stream()
                    .filter(version -> this.matchesTagFilter(version, this.zoneTagFilter))
                    .toList();
            section.child(this.zoneHistoryGraph(graphVersions));
            return section;
        }

        ProjectVersion latest = versions.getFirst();
        section.child(LumaUi.caption(Component.translatable("luma.history.current_badge")));
        section.child(this.zoneSaveCard(latest, true));

        List<ProjectVersion> olderVersions = versions.stream()
                .filter(version -> !version.id().equals(latest.id()))
                .toList();
        if (!olderVersions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.build.recent_saves_title")));
        }
        for (ProjectVersion version : olderVersions) {
            section.child(this.zoneSaveCard(version, false));
        }
        return section;
    }

    private FlowLayout zoneHistoryToolbar() {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.verticalAlignment(VerticalAlignment.CENTER);
        FlowLayout filter = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        filter.child(this.zoneTagFilter());
        row.child(filter);

        FlowLayout viewToggle = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        viewToggle.gap(4);
        ButtonComponent cards = LumaUi.iconButton("view-cards", Component.translatable("luma.history.view_cards"), !this.zoneHistoryGraphVisible, button -> {
            this.zoneHistoryGraphVisible = false;
            this.rebuild();
        });
        viewToggle.child(cards);

        ButtonComponent graph = LumaUi.iconButton("view-graph", Component.translatable("luma.history.view_graph"), this.zoneHistoryGraphVisible, button -> {
            this.zoneHistoryGraphVisible = true;
            this.rebuild();
        });
        viewToggle.child(graph);
        row.child(viewToggle);
        return row;
    }

    private FlowLayout zoneBranchStrip(List<ProjectVersion> versions) {
        FlowLayout row = LumaUi.actionRow();
        String selectedVariantId = this.selectedZoneVariantId(versions);
        this.zoneVariantIds(versions).forEach(variantId -> {
            ButtonComponent button = LumaUi.button(Component.literal(ProjectUiSupport.displayVariantName(this.state.variants(), variantId)), pressed -> {
                this.selectedZoneVariantId = variantId;
                this.rebuild();
            });
            button.active(!variantId.equals(selectedVariantId));
            row.child(button);
        });
        return row;
    }

    private FlowLayout zoneTagFilter() {
        FlowLayout row = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        row.gap(2);
        var input = UIComponents.textBox(Sizing.fixed(Math.min(180, Math.max(120, this.width / 5))), this.zoneTagFilter);
        input.setHint(Component.translatable("luma.history.tag_filter"));
        TagInputSupport.configure(input, this.zoneTagFilter, TagInputSupport.knownTags(this.state.versions()), false);
        TagSuggestionComponent suggestions = new TagSuggestionComponent(
                () -> this.zoneTagFilter,
                () -> TagInputSupport.knownTags(this.state.versions()),
                false,
                accepted -> {
                    this.zoneTagFilter = accepted;
                    input.setValue(accepted);
                    input.setCursorPosition(accepted.length());
                    this.rebuild();
                }
        );
        input.onChanged().subscribe(value -> {
            this.zoneTagFilter = TagInputSupport.limit(value);
            suggestions.refresh();
        });
        input.focusLost().subscribe(this::rebuild);
        row.child(input);
        row.child(suggestions);
        return row;
    }

    private FlowLayout zoneHistoryGraph(List<ProjectVersion> versions) {
        FlowLayout graph = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        String activeVariantId = this.state.project() == null ? "" : this.state.project().activeVariantId();
        List<CommitGraphNode> nodes = CommitGraphLayout.build(
                versions,
                this.state.variants(),
                activeVariantId
        );
        if (nodes.isEmpty()) {
            graph.child(LumaUi.caption(Component.translatable("luma.zones.history_empty")));
            return graph;
        }
        graph.child(new CommitGraphComponent(
                nodes,
                this.state.variants(),
                versionId -> this.router.openSaveDetails(this, this.effectiveProjectName(), versionId),
                this.effectiveProjectName(),
                version -> this.projectController.resolvePreviewPath(this.effectiveProjectName(), version.id())
        ));
        return graph;
    }

    private FlowLayout zoneSaveCard(ProjectVersion version, boolean latest) {
        ProjectVariant variant = ProjectUiSupport.variantFor(this.state.variants(), version.variantId());
        return this.saveCardView.render(new ProjectSaveCardView.Model(
                this.effectiveProjectName(),
                version,
                variant,
                latest,
                false,
                this.width,
                true,
                version.id().equals(this.tagEditorVersionId),
                this.tagEditorText,
                TagInputSupport.knownTags(this.state.versions())
        ));
    }

    private WorkZone focusedZone() {
        if (!this.openedZoneId.isBlank()) {
            WorkZone opened = this.state.zones().zones().stream()
                    .filter(zone -> zone.id().equals(this.openedZoneId))
                    .findFirst()
                    .orElse(null);
            if (opened != null) {
                return opened;
            }
            this.openedZoneId = "";
        }
        return this.state.zones().zones().stream()
                .filter(zone -> zone.id().equals(this.state.focusedZoneId()))
                .findFirst()
                .orElse(null);
    }

    private WorkZone activeZone() {
        if (this.state == null || this.state.zones() == null) {
            return null;
        }
        String activeZoneId = this.state.zones().activeZoneId(this.state.actor());
        return this.state.zones().zones().stream()
                .filter(zone -> zone.id().equals(activeZoneId))
                .findFirst()
                .orElse(null);
    }

    private Integer activeZoneColor() {
        WorkZone active = this.activeZone();
        return active == null ? null : (0xFF000000 | active.color());
    }

    private FlowLayout zoneColorDot(WorkZone zone) {
        FlowLayout dot = UIContainers.verticalFlow(Sizing.fixed(7), Sizing.fixed(7));
        dot.surface(Surface.flat(0xFF000000 | zone.color()));
        return dot;
    }

    private void selectZone(String zoneId) {
        this.status = this.controller.selectZone(this.effectiveProjectName(), zoneId);
        if (zoneId != null && !zoneId.isBlank()) {
            this.openedZoneId = zoneId;
        }
        this.zonePickerVisible = false;
        this.rebuild();
    }

    private void enterZone(String zoneId) {
        this.openedZoneId = zoneId == null ? "" : zoneId;
        this.selectZone(zoneId);
    }

    private void leaveZone(String zoneId) {
        this.status = this.controller.selectZone(this.effectiveProjectName(), "");
        this.openedZoneId = "";
        this.zonePickerVisible = true;
        this.rebuild();
    }

    private void openZone(String zoneId) {
        this.openedZoneId = zoneId == null ? "" : zoneId;
        this.zonePickerVisible = false;
        this.rebuild();
    }

    public void openZoneSaveDialog() {
        WorkZone active = this.activeZone();
        if (active == null) {
            this.refresh("luma.status.zone_not_found");
            return;
        }
        this.openZoneSaveDialog(active.id());
    }

    private void openZoneSaveDialog(String zoneId) {
        this.pendingSaveZoneId = zoneId == null ? "" : zoneId;
        this.saveDialogVisible = true;
        this.saveMessage = "";
        this.saveTags = "";
        this.saveTagsInput = null;
        this.refresh("luma.status.zones_ready");
    }

    private void closeZoneSaveDialog() {
        this.saveDialogVisible = false;
        this.pendingSaveZoneId = "";
        this.saveMessage = "";
        this.saveTags = "";
        this.saveTagsInput = null;
        this.refresh("luma.status.zones_ready");
    }

    private void saveZone(String zoneId, List<String> tags) {
        this.status = this.controller.saveZone(this.effectiveProjectName(), zoneId, this.saveMessage, tags);
        if ("luma.status.save_started".equals(this.status)) {
            this.client.gui.setOverlayMessage(ActionBarMessagePresenter.info(this.status), false);
            this.saveMessage = "";
            this.saveTags = "";
            this.saveDialogVisible = false;
            this.pendingSaveZoneId = "";
            this.saveTagsInput = null;
        }
        this.zonePickerVisible = false;
        this.rebuild();
    }

    private BranchCreationDialogState branchDialogState() {
        return this.branchDialogFactory.create(
                this.state == null ? List.of() : this.state.versions(),
                this.state == null ? List.of() : this.state.variants(),
                null,
                this.pendingBranchBaseVersionId,
                this.branchName
        );
    }

    private void createBranch(BranchCreationDialogState dialog) {
        if (!dialog.canCreate()) {
            return;
        }
        BranchCreationResult result = this.projectController.createAndSwitchVariant(
                this.effectiveProjectName(),
                this.branchName.trim(),
                dialog.baseVersion().id()
        );
        if (result.switched()) {
            this.selectedZoneVariantId = result.variantId();
            this.pendingBranchBaseVersionId = "";
            this.branchName = "";
        }
        this.refresh(result.statusKey());
    }

    private void closeBranchDialog() {
        this.pendingBranchBaseVersionId = "";
        this.branchName = "";
        this.refresh("luma.status.zones_ready");
    }

    private RestoreConfirmationDialogView.Model restoreDialogModel() {
        ProjectVersion version = ProjectUiSupport.versionFor(this.state.versions(), this.pendingRestoreVersionId);
        ProjectVariant variant = ProjectUiSupport.variantFor(this.state.variants(), this.pendingRestoreVariantId);
        if (version == null || variant == null) {
            this.clearPendingRestore();
            return null;
        }

        return new RestoreConfirmationDialogView.Model(
                this.width,
                Component.translatable("luma.restore.confirm_title", ProjectUiSupport.displayMessage(version)),
                Component.translatable("luma.restore.confirm_zone_help"),
                Component.translatable(
                        "luma.restore.confirm_target",
                        ProjectUiSupport.displayVariantName(variant),
                        ProjectUiSupport.displayMessage(version)
                ),
                false,
                version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT,
                false,
                false
        );
    }

    private void confirmPendingRestore() {
        ProjectVersion version = ProjectUiSupport.versionFor(this.state.versions(), this.pendingRestoreVersionId);
        if (version == null) {
            this.clearPendingRestore();
            this.refresh("luma.status.operation_failed");
            return;
        }
        String zoneId = this.versionVisibility.workZoneId(version);
        if (zoneId.isBlank()) {
            this.clearPendingRestore();
            this.refresh("luma.status.operation_failed");
            return;
        }
        this.clearPendingRestore();
        this.executeZoneRestore(version, zoneId);
    }

    private void executeZoneRestore(ProjectVersion version, String zoneId) {
        PartialRestoreRequest request = new PartialRestoreRequest(
                this.effectiveProjectName(),
                version.id(),
                null,
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.LUMI_REGION,
                this.client.getUser().getName(),
                Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId)
        );
        this.refresh(this.projectController.partialRestore(request));
    }

    private void clearPendingRestore() {
        this.pendingRestoreVariantId = "";
        this.pendingRestoreVersionId = "";
    }

    private void rebuild() {
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.zones_ready" : statusKey;
        this.rebuild();
    }

    private boolean acceptTagCompletion() {
        List<String> knownTags = TagInputSupport.knownTags(this.state.versions());
        if (!this.tagEditorVersionId.isBlank() && TagInputSupport.hasSuggestion(this.tagEditorText, knownTags)) {
            this.tagEditorText = this.activeTagInput == null
                    ? TagInputSupport.acceptSuggestion(this.tagEditorText, knownTags, true)
                    : TagInputSupport.acceptInto(this.activeTagInput, this.tagEditorText, knownTags, true);
            return true;
        }
        if (TagInputSupport.hasSuggestion(this.zoneTagFilter, knownTags)) {
            this.zoneTagFilter = TagInputSupport.acceptSuggestion(this.zoneTagFilter, knownTags, false);
            this.refresh("luma.status.zones_ready");
            return true;
        }
        return false;
    }

    private boolean acceptSaveDialogTagCompletion() {
        List<String> knownTags = TagInputSupport.knownTags(this.state.versions());
        if (!TagInputSupport.hasSuggestion(this.saveTags, knownTags)) {
            return false;
        }
        this.saveTags = this.saveTagsInput == null
                ? TagInputSupport.acceptSuggestion(this.saveTags, knownTags, true)
                : TagInputSupport.acceptInto(this.saveTagsInput, this.saveTags, knownTags, true);
        return true;
    }

    private String effectiveProjectName() {
        if (this.state != null && this.state.project() != null) {
            return this.state.project().name();
        }
        return this.projectName == null ? "" : this.projectName;
    }

    private List<ProjectVersion> zoneVersions(WorkZone zone) {
        if (zone == null) {
            return List.of();
        }
        return this.versionVisibility.zoneHistory(this.state.versions(), zone.id()).stream()
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
    }

    private List<String> zoneVariantIds(List<ProjectVersion> versions) {
        return versions.stream()
                .map(ProjectVersion::variantId)
                .filter(variantId -> variantId != null && !variantId.isBlank())
                .distinct()
                .toList();
    }

    private String selectedZoneVariantId(List<ProjectVersion> versions) {
        List<String> variantIds = this.zoneVariantIds(versions);
        if (variantIds.contains(this.selectedZoneVariantId)) {
            return this.selectedZoneVariantId;
        }
        String activeVariantId = this.state.project() == null ? "" : this.state.project().activeVariantId();
        if (variantIds.contains(activeVariantId)) {
            this.selectedZoneVariantId = activeVariantId;
            return activeVariantId;
        }
        this.selectedZoneVariantId = variantIds.isEmpty() ? "" : variantIds.getFirst();
        return this.selectedZoneVariantId;
    }

    private int sharedCellCount(WorkZone zone) {
        if (zone == null || zone.cells().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (WorkZoneCell cell : zone.cells()) {
            boolean shared = this.state.zones().zones().stream()
                    .anyMatch(other -> !other.id().equals(zone.id()) && other.contains(cell));
            if (shared) {
                count++;
            }
        }
        return count;
    }

    private static String colorHex(int color) {
        return "#" + String.format(java.util.Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    private boolean matchesTagFilter(ProjectVersion version, String filter) {
        String needle = filter == null ? "" : filter.trim().replaceFirst("^#+", "").toLowerCase(java.util.Locale.ROOT);
        return needle.isBlank() || ProjectVersionTags.from(version).stream()
                .anyMatch(tag -> tag.toLowerCase(java.util.Locale.ROOT).contains(needle));
    }

    private final class RestoreDialogActions implements RestoreConfirmationDialogView.Actions {

        @Override
        public void cancel() {
            clearPendingRestore();
            refresh("luma.status.zones_ready");
        }

        @Override
        public void restoreWhole() {
            confirmPendingRestore();
        }

        @Override
        public void restoreSelectedArea() {
            confirmPendingRestore();
        }

        @Override
        public void restoreOutsideSelection() {
            confirmPendingRestore();
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

    private final class ZoneSaveCardActions implements ProjectSaveCardView.Actions {

        @Override
        public void openSaveDetails(String versionId) {
            router.openSaveDetails(WorkZoneScreen.this, effectiveProjectName(), versionId);
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
        public void openBranchDialog(ProjectVersion version) {
            pendingBranchBaseVersionId = version == null ? "" : version.id();
            branchName = "";
            refresh("luma.status.zones_ready");
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
            refresh("luma.status.zones_ready");
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
            String result = projectController.updateVersionTags(
                    effectiveProjectName(),
                    version.id(),
                    ProjectVersionTags.parse(tagEditorText)
            );
            if ("luma.status.tags_updated".equals(result)) {
                tagEditorVersionId = "";
                tagEditorText = "";
            }
            refresh(result);
        }
    }
}
