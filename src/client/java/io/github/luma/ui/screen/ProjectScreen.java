package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
import io.github.luma.ui.tab.ChangesTabView;
import io.github.luma.ui.tab.HistoryTabView;
import io.github.luma.ui.tab.IntegrationsTabView;
import io.github.luma.ui.tab.LogTabView;
import io.github.luma.ui.tab.MaterialsTabView;
import io.github.luma.ui.tab.PreviewTabView;
import io.github.luma.ui.tab.VariantsTabView;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private ProjectViewState state = new ProjectViewState(
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            new io.github.luma.domain.model.ProjectIntegrityReport(true, List.of(), List.of()),
            ProjectTab.HISTORY,
            "luma.status.project_ready"
    );
    private String statusKey;
    private String saveMessage = "";
    private String variantName = "";
    private String variantBaseVersionId = "";
    private String selectedVersionId = "";

    public ProjectScreen(Screen parent, String projectName) {
        this(parent, projectName, "luma.status.project_ready");
    }

    public ProjectScreen(Screen parent, String projectName, String statusKey) {
        super(Component.literal(projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.statusKey = statusKey;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.state.selectedTab(), this.selectedVersionId, this.statusKey);
        if (this.state.selectedVersion() != null) {
            this.selectedVersionId = this.state.selectedVersion().id();
        }

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout shell = LumaUi.panel(Sizing.fill(100), Sizing.content());
        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), shell));

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.save_version"), button -> {
            this.statusKey = this.controller.saveVersion(this.projectName, this.saveMessage);
            this.refresh(this.state.selectedTab(), this.statusKey);
        }));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.refresh(this.state.selectedTab(), this.statusKey)));
        if (this.state.recoveryDraft() != null) {
            header.child(UIComponents.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, this.projectName)));
        }
        if (this.state.selectedVersion() != null) {
            header.child(UIComponents.button(Component.translatable("luma.action.compare"), button -> this.router.openCompare(
                    this,
                    this.projectName,
                    this.state.selectedVersion().parentVersionId(),
                    this.state.selectedVersion().id()
            )));
        }
        header.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, this.projectName)));
        shell.child(header);

        FlowLayout titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleRow.gap(8);
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.project.title", this.projectName)));
        titleRow.child(LumaUi.chip(Component.translatable(this.state.status())));
        shell.child(titleRow);
        shell.child(LumaUi.caption(Component.translatable("luma.project.workspace_hint")));

        if (this.state.project() == null) {
            shell.child(LumaUi.caption(Component.translatable("luma.project.unavailable")));
            return;
        }

        FlowLayout metrics = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        metrics.gap(6);
        metrics.child(LumaUi.metric(Component.translatable("luma.project.metric_branch"), Component.literal(this.state.project().activeVariantId())));
        metrics.child(LumaUi.metric(Component.translatable("luma.project.metric_versions"), Component.literal(Integer.toString(this.state.versions().size()))));
        metrics.child(LumaUi.metric(Component.translatable("luma.project.metric_pending"), Component.literal(Integer.toString(this.state.recoveryDraft() == null ? 0 : this.state.recoveryDraft().changes().size()))));
        metrics.child(LumaUi.metric(Component.translatable("luma.project.metric_branches"), Component.literal(Integer.toString(this.state.variants().size()))));
        shell.child(metrics);

        FlowLayout meta = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        meta.child(LumaUi.caption(Component.translatable("luma.project.dimension", this.dimensionLabel(this.state.project().dimensionId()))));
        meta.child(LumaUi.caption(Component.translatable(
                this.state.project().tracksWholeDimension() ? "luma.project.scope_world" : "luma.project.scope_bounds"
        )));
        if (!this.state.project().tracksWholeDimension() && this.state.project().bounds() != null) {
            meta.child(LumaUi.caption(Component.translatable(
                    "luma.project.bounds",
                    this.state.project().bounds().min().x(),
                    this.state.project().bounds().min().y(),
                    this.state.project().bounds().min().z(),
                    this.state.project().bounds().max().x(),
                    this.state.project().bounds().max().y(),
                    this.state.project().bounds().max().z()
            )));
        }
        shell.child(meta);

        if (this.state.selectedVersion() != null) {
            FlowLayout selectedStrip = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            selectedStrip.child(LumaUi.accent(Component.translatable("luma.project.selected_version_short", this.state.selectedVersion().id())));
            selectedStrip.child(LumaUi.value(Component.literal(this.state.selectedVersion().message())));
            selectedStrip.child(LumaUi.caption(Component.translatable(
                    "luma.history.version_changes",
                    this.state.selectedVersion().stats().changedBlocks(),
                    this.state.selectedVersion().stats().changedChunks(),
                    this.state.selectedVersion().stats().distinctBlockTypes()
            )));
            shell.child(selectedStrip);
        } else if (this.state.recoveryDraft() != null) {
            shell.child(LumaUi.accent(Component.translatable(
                    "luma.project.pending_changes_hint",
                    this.state.recoveryDraft().changes().size(),
                    this.state.project().activeVariantId()
            )));
        } else {
            shell.child(LumaUi.caption(Component.translatable("luma.project.no_versions_hint")));
        }

        FlowLayout tabs = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout tabRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        tabRow.gap(6);
        tabs.gap(6);
        for (ProjectTab tab : ProjectTab.values()) {
            var button = UIComponents.button(Component.translatable(tab.translationKey()), pressed -> this.refresh(tab, this.statusKey));
            button.active(tab != this.state.selectedTab());
            tabRow.child(button);
        }
        tabs.child(tabRow);
        shell.child(tabs);

        FlowLayout content = switch (this.state.selectedTab()) {
            case HISTORY -> HistoryTabView.build(
                    this.state,
                    this.controller,
                    this.projectName,
                    () -> this.saveMessage,
                    value -> this.saveMessage = value,
                    versionId -> this.refresh(ProjectTab.HISTORY, versionId, this.statusKey),
                    versionId -> this.router.openCompare(
                            this,
                            this.projectName,
                            this.state.selectedVersion() == null ? "" : this.state.selectedVersion().id(),
                            versionId
                    ),
                    status -> this.refresh(ProjectTab.HISTORY, status)
            );
            case VARIANTS -> VariantsTabView.build(
                    this.state,
                    this.controller,
                    this.projectName,
                    () -> this.variantName,
                    () -> this.variantBaseVersionId,
                    value -> this.variantName = value,
                    value -> this.variantBaseVersionId = value,
                    status -> this.refresh(ProjectTab.VARIANTS, status)
            );
            case CHANGES -> ChangesTabView.build(this.state);
            case PREVIEW -> PreviewTabView.build(
                    this.state,
                    this.projectName,
                    this.controller,
                    status -> this.refresh(ProjectTab.PREVIEW, this.selectedVersionId, status)
            );
            case MATERIALS -> MaterialsTabView.build(this.state);
            case INTEGRATIONS -> IntegrationsTabView.build(this.state);
            case LOG -> LogTabView.build(this.state);
        };

        shell.child(content);
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private void refresh(ProjectTab tab, String statusKey) {
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.uiAdapter.rootComponent.clearChildren();
        this.state = new ProjectViewState(
                this.state.project(),
                this.state.versions(),
                this.state.variants(),
                this.state.journal(),
                this.state.recoveryDraft(),
                this.state.selectedVersion(),
                this.state.selectedVersionDiff(),
                this.state.materialDelta(),
                this.state.integrations(),
                this.state.integrityReport(),
                tab,
                this.statusKey
        );
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private void refresh(ProjectTab tab, String selectedVersionId, String statusKey) {
        this.selectedVersionId = selectedVersionId == null ? "" : selectedVersionId;
        this.refresh(tab, statusKey);
    }

    private String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }
}
