package io.github.luma.ui.screen;

import io.github.luma.client.onboarding.ClientContextualHelpHint;
import io.github.luma.client.onboarding.ClientContextualHelpService;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.ui.ContextualHelpPresenter;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
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
import io.wispforest.owo.ui.core.Surface;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VariantsScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String baseVersionId;
    private final boolean ignoreActiveZone;
    private final Minecraft client = Minecraft.getInstance();
    private final VariantsScreenController stateController = new VariantsScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ClientContextualHelpService contextualHelpService = new ClientContextualHelpService();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private VariantsViewState state = new VariantsViewState(null, List.of(), List.of(), null, null, "luma.status.project_ready");
    private String status = "luma.status.project_ready";
    private String variantName = "";
    private String pendingDeleteVariantId = "";
    private String deleteVariantName = "";
    private TextBoxComponent variantNameInput;
    private ButtonComponent createVariantButton;
    private int refreshCooldown = 0;

    public VariantsScreen(Screen parent, String projectName) {
        this(parent, projectName, "");
    }

    public VariantsScreen(Screen parent, String projectName, String baseVersionId) {
        this(parent, projectName, baseVersionId, false);
    }

    public VariantsScreen(Screen parent, String projectName, String baseVersionId, boolean ignoreActiveZone) {
        super(Component.translatable("luma.screen.ideas.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.baseVersionId = baseVersionId == null ? "" : baseVersionId;
        this.ignoreActiveZone = ignoreActiveZone;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.loadState();
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
                this.zoneMode()
                        ? Component.translatable("luma.screen.zone_ideas.title", this.activeZone().name())
                        : Component.translatable("luma.screen.ideas.title", this.projectName),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(
                window,
                this,
                this.projectName,
                ProjectWorkspaceTab.VARIANTS,
                this.zoneMode() ? this.zoneColorArgb() : null
        );
        if (this.zoneMode()) {
            window.titleBar().child(LumaUi.iconButton("arrow-left", Component.translatable("luma.action.back"), button ->
                    this.router.openVariantsIgnoringActiveZone(this.parent, this.projectName)));
        }
        if (this.shouldShowStatusBanner()) {
            window.content().child(LumaUi.statusBanner(this.bannerText()));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        new ContextualHelpPresenter(this.contextualHelpService, this::rebuild)
                .addHint(body, ClientContextualHelpHint.BRANCHES);
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
    public Screen navigationParent() {
        return this.parent;
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        VariantsViewState refreshed = this.loadState();
        String normalizedStatus = ScreenOperationStateSupport.normalizeStatusKey(
                this.status,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatus.equals(this.status)) {
            this.status = normalizedStatus;
            refreshed = this.loadState();
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.rebuild();
        }
    }

    private FlowLayout overviewSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable(this.zoneMode() ? "luma.ideas.zone_overview_title" : "luma.ideas.overview_title"),
                Component.translatable(this.zoneMode() ? "luma.ideas.zone_overview_help" : "luma.ideas.overview_help")
        );
        if (this.zoneMode()) {
            section.child(LumaUi.chip(Component.translatable("luma.ideas.zone_badge", this.activeZone().name())));
        }
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
                        this.zoneMode() ? "luma.ideas.zone_create_help" : "luma.ideas.create_help",
                        baseVersion == null ? Component.translatable(this.zoneMode() ? "luma.ideas.zone_no_base" : "luma.ideas.current_build_base") : Component.literal(ProjectUiSupport.displayMessage(baseVersion))
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
            String baseId = baseVersion == null ? this.baseVersionId : baseVersion.id();
            String result = this.actionController.createVariant(this.projectName, this.variantName.trim(), baseId);
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
                Component.translatable(this.zoneMode() ? "luma.ideas.zone_list_title" : "luma.ideas.list_title"),
                Component.translatable(this.zoneMode() ? "luma.ideas.zone_list_help" : "luma.ideas.list_help")
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
        ProjectVersion headVersion = this.headVersion(variant);
        boolean active = this.state.project() != null && variant.id().equals(this.state.project().activeVariantId());

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        if (this.zoneMode()) {
            card.surface(Surface.flat(0xEA101113).and(Surface.outline(this.zoneColorArgb())));
        }
        card.child(LumaUi.value(Component.literal(ProjectUiSupport.displayVariantName(variant))));
        if (headVersion == null) {
            card.child(LumaUi.caption(Component.translatable(this.zoneMode() ? "luma.ideas.zone_no_saves" : "luma.ideas.no_saves")));
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
                if (this.zoneMode()) {
                    this.refresh(result);
                } else {
                    this.router.openProjectIgnoringRecovery(this.parent, this.projectName, variant.id(), result);
                }
            } else {
                this.refresh(result);
            }
        });
        switchButton.active(!active && !this.operationActive());
        actions.child(switchButton);

        if (!this.zoneMode()) {
            ButtonComponent mergeButton = LumaUi.button(Component.translatable("luma.action.merge_into_current"), button -> {
                String result = this.actionController.mergeVariantIntoCurrent(this.projectName, variant.id());
                this.refresh(result);
            });
            mergeButton.active(!active && headVersion != null && !this.operationActive());
            actions.child(mergeButton);
        }

        boolean protectedMain = variant.main() || Objects.equals("main", variant.id());
        ButtonComponent deleteButton = LumaUi.iconButton("trash-2", Component.translatable("luma.action.delete_branch"), button -> {
            this.pendingDeleteVariantId = variant.id();
            this.deleteVariantName = "";
            this.rebuild();
        });
        deleteButton.active(!active && !protectedMain && !this.operationActive());
        actions.child(deleteButton);
        card.child(actions);
        if (variant.id().equals(this.pendingDeleteVariantId)) {
            card.child(this.deleteConfirmation(variant));
        }
        return card;
    }

    private VariantsViewState loadState() {
        return this.stateController.loadState(this.projectName, this.status, !this.ignoreActiveZone);
    }

    private ProjectVersion resolvedBaseVersion() {
        if (!this.baseVersionId.isBlank()) {
            return ProjectUiSupport.versionFor(this.state.versions(), this.baseVersionId);
        }
        if (this.zoneMode() && this.state.project() != null) {
            return this.latestVersionForVariant(this.state.project().activeVariantId());
        }
        return ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
    }

    private ProjectVersion headVersion(ProjectVariant variant) {
        if (variant == null) {
            return null;
        }
        return this.zoneMode()
                ? this.latestVersionForVariant(variant.id())
                : ProjectUiSupport.versionFor(this.state.versions(), variant.headVersionId());
    }

    private ProjectVersion latestVersionForVariant(String variantId) {
        return this.state.versions().stream()
                .filter(version -> variantId != null && variantId.equals(version.variantId()))
                .findFirst()
                .orElse(null);
    }

    private FlowLayout deleteConfirmation(ProjectVariant variant) {
        FlowLayout panel = LumaUi.insetSection(
                Component.translatable("luma.ideas.delete_confirm_title", ProjectUiSupport.displayVariantName(variant)),
                Component.translatable("luma.ideas.delete_confirm_help", ProjectUiSupport.displayVariantName(variant))
        );
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.deleteVariantName);
        input.setHint(Component.literal(ProjectUiSupport.displayVariantName(variant)));
        ButtonComponent confirm = LumaUi.primaryButton(Component.translatable("luma.action.delete_branch"), button -> this.confirmDeleteVariant(variant));
        input.onChanged().subscribe(value -> {
            this.deleteVariantName = value == null ? "" : value;
            confirm.active(this.canConfirmDelete(variant));
        });
        panel.child(LumaUi.formField(
                Component.translatable("luma.idea.name_input"),
                Component.translatable("luma.ideas.delete_confirm_input_help"),
                input
        ));
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> {
            this.pendingDeleteVariantId = "";
            this.deleteVariantName = "";
            this.rebuild();
        }));
        confirm.active(this.canConfirmDelete(variant));
        actions.child(confirm);
        panel.child(actions);
        return panel;
    }

    private boolean canConfirmDelete(ProjectVariant variant) {
        return variant != null
                && variant.id().equals(this.pendingDeleteVariantId)
                && ProjectUiSupport.displayVariantName(variant).equals(this.deleteVariantName.trim());
    }

    private void confirmDeleteVariant(ProjectVariant variant) {
        if (!this.canConfirmDelete(variant)) {
            return;
        }
        String result = this.actionController.deleteVariant(this.projectName, variant.id());
        this.pendingDeleteVariantId = "";
        this.deleteVariantName = "";
        this.refresh(result);
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
            this.createVariantButton.active(!this.variantName.trim().isBlank()
                    && !this.operationActive()
                    && (!this.zoneMode() || this.resolvedBaseVersion() != null));
        }
    }

    private boolean zoneMode() {
        return this.activeZone() != null;
    }

    private WorkZone activeZone() {
        return this.state == null ? null : this.state.activeZone();
    }

    private int zoneColorArgb() {
        return this.zoneMode() ? 0xFF000000 | this.activeZone().color() : 0xFF2B2A2F;
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
        this.rebuildPreservingScroll(() -> this.bodyScroll);
    }
}
