package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.controller.VariantsScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.VariantsViewState;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VariantsScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String baseVersionId;
    private final Minecraft client = Minecraft.getInstance();
    private final VariantsScreenController stateController = new VariantsScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private VariantsViewState state = new VariantsViewState(null, List.of(), List.of(), null, "luma.status.project_ready");
    private String status = "luma.status.project_ready";
    private String variantName = "";
    private TextBoxComponent variantNameInput;
    private ButtonComponent createVariantButton;
    private int refreshCooldown = 0;

    public VariantsScreen(Screen parent, String projectName) {
        this(parent, projectName, "");
    }

    public VariantsScreen(Screen parent, String projectName, String baseVersionId) {
        super(Component.translatable("luma.screen.ideas.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.baseVersionId = baseVersionId == null ? "" : baseVersionId;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.stateController.loadState(this.projectName, this.status);
        ProjectVersion baseVersion = this.resolvedBaseVersion();

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

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.ideas.title", this.projectName),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.VARIANTS);
        if (this.shouldShowStatusBanner()) {
            window.content().child(LumaUi.statusBanner(this.bannerText()));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        body.child(this.overviewSection());
        body.child(this.createSection(baseVersion));
        body.child(this.listSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        VariantsViewState refreshed = this.stateController.loadState(this.projectName, this.status);
        String normalizedStatus = ScreenOperationStateSupport.normalizeStatusKey(
                this.status,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatus.equals(this.status)) {
            this.status = normalizedStatus;
            refreshed = this.stateController.loadState(this.projectName, this.status);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.rebuild();
        }
    }

    private FlowLayout overviewSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.ideas.overview_title"),
                Component.translatable("luma.ideas.overview_help")
        );
        section.child(LumaUi.chip(Component.translatable(
                "luma.build.current_idea",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));

        if (this.state.operationSnapshot() != null) {
            section.child(this.operationSection());
        }
        return section;
    }

    private FlowLayout createSection(ProjectVersion baseVersion) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.ideas.create_title"),
                Component.translatable(
                        "luma.ideas.create_help",
                        baseVersion == null ? Component.translatable("luma.ideas.current_build_base") : Component.literal(ProjectUiSupport.displayMessage(baseVersion))
                )
        );

        this.createVariantButton = null;
        this.variantNameInput = UIComponents.textBox(Sizing.fill(100), this.variantName);
        this.variantNameInput.setHint(Component.translatable("luma.idea.name_input"));
        this.variantNameInput.onChanged().subscribe(value -> {
            this.variantName = value == null ? "" : value;
            this.updateCreateButtonActive();
        });
        section.child(LumaUi.formField(
                Component.translatable("luma.idea.name_input"),
                Component.translatable("luma.ideas.name_help"),
                this.variantNameInput
        ));

        FlowLayout actions = LumaUi.actionRow();
        this.createVariantButton = LumaUi.primaryButton(Component.translatable("luma.action.create_idea"), button -> {
            String result = this.actionController.createVariant(this.projectName, this.variantName.trim(), this.baseVersionId);
            if ("luma.status.variant_created".equals(result)) {
                this.variantName = "";
            }
            this.refresh(result);
        });
        this.updateCreateButtonActive();
        actions.child(this.createVariantButton);
        section.child(actions);
        return section;
    }

    private FlowLayout listSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.ideas.list_title"),
                Component.translatable("luma.ideas.list_help")
        );

        if (this.state.variants().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.idea.empty")));
            return section;
        }

        for (ProjectVariant variant : this.sortedVariants()) {
            section.child(this.variantCard(variant));
        }
        return section;
    }

    private FlowLayout variantCard(ProjectVariant variant) {
        ProjectVersion headVersion = ProjectUiSupport.versionFor(this.state.versions(), variant.headVersionId());
        boolean active = this.state.project() != null && variant.id().equals(this.state.project().activeVariantId());

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.literal(ProjectUiSupport.displayVariantName(variant))));
        if (headVersion == null) {
            card.child(LumaUi.caption(Component.translatable("luma.ideas.no_saves")));
        } else {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.ideas.latest_save",
                    ProjectUiSupport.displayMessage(headVersion),
                    ProjectUiSupport.formatTimestamp(headVersion.createdAt())
            )));
        }

        FlowLayout meta = LumaUi.actionRow();
        if (active) {
            meta.child(LumaUi.chip(Component.translatable("luma.idea.current_badge")));
        }
        card.child(meta);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent switchButton = LumaUi.primaryButton(Component.translatable("luma.action.switch_idea"), button -> {
            String result = this.actionController.switchVariant(this.projectName, variant.id());
            if ("luma.status.variant_switched".equals(result)) {
                this.router.openProjectIgnoringRecovery(this.parent, this.projectName, variant.id(), result);
            } else {
                this.refresh(result);
            }
        });
        switchButton.active(!active && !this.operationActive());
        actions.child(switchButton);

        actions.child(LumaUi.button(Component.translatable("luma.action.open_saves"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName,
                variant.id(),
                "luma.status.project_ready"
        )));

        ButtonComponent compareButton = LumaUi.button(Component.translatable("luma.action.see_changes"), button -> this.router.openCompare(
                this,
                this.projectName,
                variant.headVersionId(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                variant.headVersionId()
        ));
        compareButton.active(headVersion != null);
        actions.child(compareButton);

        ButtonComponent mergeButton = LumaUi.button(Component.translatable("luma.action.merge_into_current"), button -> {
            String result = this.actionController.mergeVariantIntoCurrent(this.projectName, variant.id());
            this.refresh(result);
        });
        mergeButton.active(!active && headVersion != null && !this.operationActive());
        actions.child(mergeButton);

        ButtonComponent deleteButton = LumaUi.button(Component.translatable("luma.action.delete_branch"), button -> {
            String result = this.actionController.deleteVariant(this.projectName, variant.id());
            this.refresh(result);
        });
        deleteButton.active(!active && !variant.main() && !"main".equals(variant.id()) && !this.operationActive());
        actions.child(deleteButton);
        card.child(actions);
        return card;
    }

    private ProjectVersion resolvedBaseVersion() {
        if (!this.baseVersionId.isBlank()) {
            return ProjectUiSupport.versionFor(this.state.versions(), this.baseVersionId);
        }
        return ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
    }

    private List<ProjectVariant> sortedVariants() {
        return this.state.variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private FlowLayout operationSection() {
        var operation = this.state.operationSnapshot();
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.project.operation_title"),
                Component.literal(OperationProgressPresenter.progressSummary(operation))
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.project.operation_stage",
                operation.stage().name().toLowerCase(java.util.Locale.ROOT)
        )));
        section.child(LumaUi.caption(Component.translatable(
                "luma.project.operation_percent_label",
                OperationProgressPresenter.displayPercent(operation)
        )));
        if (operation.detail() != null && !operation.detail().isBlank()) {
            section.child(LumaUi.caption(Component.literal(operation.detail())));
        }
        return section;
    }

    private boolean operationActive() {
        return ScreenOperationStateSupport.blocksMutationActions(this.state.operationSnapshot());
    }

    private void updateCreateButtonActive() {
        if (this.createVariantButton != null) {
            this.createVariantButton.active(!this.variantName.trim().isBlank() && !this.operationActive());
        }
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

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        double scrollProgress = this.currentScrollProgress();
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
    }

    private double currentScrollProgress() {
        return this.bodyScroll == null ? 0.0D : this.bodyScroll.progress();
    }

    private void restoreScroll(double scrollProgress) {
        if (this.bodyScroll != null) {
            this.bodyScroll.restoreProgress(scrollProgress);
        }
    }
}
