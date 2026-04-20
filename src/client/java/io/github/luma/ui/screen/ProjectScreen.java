package io.github.luma.ui.screen;

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

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
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
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.project.title", this.projectName)).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.state.status())));

        if (this.state.project() == null) {
            root.child(UIComponents.label(Component.translatable("luma.project.unavailable")));
            return;
        }

        root.child(UIComponents.label(Component.translatable(
                "luma.project.dimension",
                this.state.project().dimensionId()
        )));
        root.child(UIComponents.label(Component.translatable(
                "luma.project.active_variant",
                this.state.project().activeVariantId()
        )));
        root.child(UIComponents.label(Component.translatable(
                "luma.project.project_flags",
                this.state.project().favorite() ? Component.translatable("luma.common.yes") : Component.translatable("luma.common.no"),
                this.state.project().archived() ? Component.translatable("luma.common.yes") : Component.translatable("luma.common.no")
        )));
        root.child(UIComponents.label(Component.translatable(
                "luma.project.bounds",
                this.state.project().bounds().min().x(),
                this.state.project().bounds().min().y(),
                this.state.project().bounds().min().z(),
                this.state.project().bounds().max().x(),
                this.state.project().bounds().max().y(),
                this.state.project().bounds().max().z()
        )));
        if (this.state.selectedVersion() != null) {
            root.child(UIComponents.label(Component.translatable(
                    "luma.project.selected_version",
                    this.state.selectedVersion().id(),
                    this.state.selectedVersion().message()
            )));
        }

        FlowLayout tabs = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        tabs.gap(6);
        for (ProjectTab tab : ProjectTab.values()) {
            tabs.child(UIComponents.button(Component.translatable(tab.translationKey()), button -> this.refresh(tab, this.statusKey)));
        }
        root.child(tabs);

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

        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), content));
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
}
