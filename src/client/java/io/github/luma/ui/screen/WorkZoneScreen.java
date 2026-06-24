package io.github.luma.ui.screen;

import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.service.ProjectVersionVisibility;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.WorkZoneScreenController;
import io.github.luma.ui.graph.CommitGraphComponent;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.screen.section.ProjectSaveCardView;
import io.github.luma.ui.state.WorkZoneViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorkZoneScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final WorkZoneScreenController controller = new WorkZoneScreenController();
    private final ProjectScreenController projectController = new ProjectScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ProjectVersionVisibility versionVisibility = new ProjectVersionVisibility();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSaveCardView saveCardView = new ProjectSaveCardView(this.projectController, new ZoneSaveCardActions());
    private WorkZoneViewState state;
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private String status = "luma.status.zones_ready";
    private String newZoneName = "";
    private String saveMessage = "";
    private boolean zonePickerVisible;
    private boolean zoneHistoryGraphVisible;

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

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.zones.title", this.effectiveProjectName()),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.effectiveProjectName(), ProjectWorkspaceTab.ZONES);
        if (!"luma.status.zones_ready".equals(this.state.status())) {
            window.content().child(LumaUi.statusBanner(Component.translatable(this.state.status())));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);
        WorkZone focused = this.focusedZone();
        if (focused != null && !this.zonePickerVisible) {
            boolean active = focused.id().equals(this.state.zones().activeZoneId(this.state.actor()));
            body.child(this.zoneDetailSection(focused, active));
            body.child(this.detailActions(active));
            body.child(this.saveZoneSection(focused, active));
            body.child(this.zoneHistorySection(focused));
            body.child(LumaUi.bottomSpacer());
            return;
        }
        body.child(this.createZoneSection());
        body.child(this.zoneListSection());
        body.child(LumaUi.bottomSpacer());
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
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.list_title"),
                Component.translatable("luma.zones.list_help")
        );
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
        card.child(LumaUi.value(Component.literal(zone.name())));
        card.child(LumaUi.caption(Component.translatable(
                "luma.zones.zone_meta",
                colorHex(zone.color()),
                zone.cells().size()
        )));
        if (zone.cells().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.zones.zone_draft")));
        }
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent select = LumaUi.button(Component.translatable(active ? "luma.zones.active" : "luma.zones.enter"), button ->
                this.selectZone(zone.id()));
        select.active(!active);
        actions.child(select);
        card.child(actions);
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
        ButtonComponent enter = LumaUi.primaryButton(Component.translatable(active ? "luma.zones.active" : "luma.zones.enter"), button ->
                this.selectZone(zone.id()));
        enter.active(!active);
        actions.child(enter);
        section.child(actions);
        return section;
    }

    private FlowLayout detailActions(boolean active) {
        FlowLayout actions = LumaUi.actionRow();
        if (active) {
            actions.child(LumaUi.button(Component.translatable("luma.zones.leave"), button -> this.selectZone("")));
        }
        actions.child(LumaUi.button(Component.translatable("luma.action.back"), button -> {
            this.zonePickerVisible = true;
            this.rebuild();
        }));
        return actions;
    }

    private FlowLayout saveZoneSection(WorkZone zone, boolean active) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.save_title"),
                Component.translatable(active ? "luma.zones.save_help" : "luma.zones.save_enter_first")
        );
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        input.setHint(Component.translatable("luma.zones.save_input"));
        input.onChanged().subscribe(value -> this.saveMessage = value == null ? "" : value);
        section.child(input);
        ButtonComponent save = LumaUi.primaryButton(Component.translatable("luma.zones.save_button"), button ->
                this.saveZone(zone.id()));
        save.active(active);
        section.child(save);
        return section;
    }

    private FlowLayout zoneHistorySection(WorkZone zone) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.zones.history_title"),
                Component.translatable("luma.zones.history_help")
        );
        List<ProjectVersion> versions = this.zoneVersions(zone);
        if (versions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.zones.history_empty")));
            return section;
        }
        section.child(this.zoneHistoryViewToggle());
        if (this.zoneHistoryGraphVisible) {
            section.child(this.zoneHistoryGraph(versions));
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

    private FlowLayout zoneHistoryViewToggle() {
        FlowLayout row = LumaUi.actionRow();
        ButtonComponent cards = LumaUi.button(Component.translatable("luma.history.view_cards"), button -> {
            this.zoneHistoryGraphVisible = false;
            this.rebuild();
        });
        cards.active(this.zoneHistoryGraphVisible);
        row.child(cards);

        ButtonComponent graph = LumaUi.button(Component.translatable("luma.history.view_graph"), button -> {
            this.zoneHistoryGraphVisible = true;
            this.rebuild();
        });
        graph.active(!this.zoneHistoryGraphVisible);
        row.child(graph);
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
                versionId -> this.router.openSaveDetails(this, this.effectiveProjectName(), versionId)
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
                false
        ));
    }

    private WorkZone focusedZone() {
        return this.state.zones().zones().stream()
                .filter(zone -> zone.id().equals(this.state.focusedZoneId()))
                .findFirst()
                .orElse(null);
    }

    private void selectZone(String zoneId) {
        this.status = this.controller.selectZone(this.effectiveProjectName(), zoneId);
        this.zonePickerVisible = false;
        this.rebuild();
    }

    private void saveZone(String zoneId) {
        this.status = this.controller.saveZone(this.effectiveProjectName(), zoneId, this.saveMessage);
        if ("luma.status.save_started".equals(this.status)) {
            this.client.gui.setOverlayMessage(ActionBarMessagePresenter.info(this.status), false);
            this.saveMessage = "";
        }
        this.zonePickerVisible = false;
        this.rebuild();
    }

    private void rebuild() {
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.zones_ready" : statusKey;
        this.rebuild();
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

    private static String colorHex(int color) {
        return "#" + String.format(java.util.Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    private final class ZoneSaveCardActions implements ProjectSaveCardView.Actions {

        @Override
        public void openSaveDetails(String versionId) {
            router.openSaveDetails(WorkZoneScreen.this, effectiveProjectName(), versionId);
        }

        @Override
        public void requestRestore(ProjectVariant variant, ProjectVersion version) {
            String zoneId = versionVisibility.workZoneId(version);
            if (zoneId.isBlank()) {
                refresh("luma.status.operation_failed");
                return;
            }
            PartialRestoreRequest request = new PartialRestoreRequest(
                    effectiveProjectName(),
                    version.id(),
                    null,
                    PartialRestoreMode.SELECTED_AREA,
                    PartialRestoreRegionSource.LUMI_REGION,
                    client.getUser().getName(),
                    Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId)
            );
            refresh(projectController.partialRestore(request));
        }

        @Override
        public void openBranchDialog(ProjectVersion version) {
            router.openSaveDetails(WorkZoneScreen.this, effectiveProjectName(), version.id());
        }
    }
}
