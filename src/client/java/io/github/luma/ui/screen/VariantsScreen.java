package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VariantsScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String baseVersionId;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private LumaScrollContainer<FlowLayout> bodyScroll;
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
            null,
            new io.github.luma.domain.model.ProjectIntegrityReport(true, List.of(), List.of()),
            ProjectTab.VARIANTS,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private String variantName = "";
    private TextBoxComponent variantNameInput;

    public VariantsScreen(Screen parent, String projectName) {
        this(parent, projectName, "");
    }

    public VariantsScreen(Screen parent, String projectName, String baseVersionId) {
        super(Component.translatable("luma.screen.variants.title", projectName));
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
        this.state = this.controller.loadState(this.projectName, ProjectTab.VARIANTS, this.baseVersionId, this.status);
        ProjectVersion baseVersion = this.resolvedBaseVersion();

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName
        )));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.variants.title", this.projectName)));
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (this.state.project() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        body.child(this.overviewSection());
        body.child(this.createSection(baseVersion));
        body.child(this.listSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout overviewSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.overview_title"),
                Component.translatable("luma.variants.overview_help")
        );
        section.child(LumaUi.chip(Component.translatable(
                "luma.project.active_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));
        return section;
    }

    private FlowLayout createSection(ProjectVersion baseVersion) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.create_title"),
                Component.translatable(
                        "luma.variants.create_help",
                        baseVersion == null ? Component.translatable("luma.variants.current_variant_base") : Component.literal(ProjectUiSupport.displayMessage(baseVersion))
                )
        );

        this.variantNameInput = UIComponents.textBox(Sizing.fill(100), this.variantName);
        this.variantNameInput.setHint(Component.translatable("luma.variant.name_input"));
        this.variantNameInput.onChanged().subscribe(value -> this.variantName = value);
        section.child(LumaUi.formField(
                Component.translatable("luma.variant.name_input"),
                Component.translatable("luma.variants.name_help"),
                this.variantNameInput
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent createButton = UIComponents.button(Component.translatable("luma.action.variant_create"), button -> {
            String result = this.controller.createVariant(this.projectName, this.variantName, this.baseVersionId);
            if ("luma.status.variant_created".equals(result)) {
                this.variantName = "";
            }
            this.refresh(result);
        });
        createButton.active(!this.variantName.isBlank());
        actions.child(createButton);
        section.child(actions);
        return section;
    }

    private FlowLayout listSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.list_title"),
                Component.translatable("luma.variants.list_help")
        );

        if (this.state.variants().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.variant.empty")));
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
            card.child(LumaUi.caption(Component.translatable("luma.variants.no_head")));
        } else {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.variants.head_summary",
                    ProjectUiSupport.displayMessage(headVersion),
                    ProjectUiSupport.formatTimestamp(headVersion.createdAt())
            )));
        }

        FlowLayout meta = LumaUi.actionRow();
        if (active) {
            meta.child(LumaUi.chip(Component.translatable("luma.variant.active_badge")));
        }
        if (variant.baseVersionId() != null && !variant.baseVersionId().isBlank()) {
            meta.child(LumaUi.chip(Component.translatable("luma.variants.base_badge", variant.baseVersionId())));
        }
        card.child(meta);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent switchButton = UIComponents.button(Component.translatable("luma.action.variant_switch"), button -> {
            String result = this.controller.switchVariant(this.projectName, variant.id());
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, variant.id(), result);
        });
        switchButton.active(!active);
        actions.child(switchButton);

        actions.child(UIComponents.button(Component.translatable("luma.action.open_history"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName,
                variant.id(),
                "luma.status.project_ready"
        )));

        ButtonComponent compareButton = UIComponents.button(Component.translatable("luma.variants.compare_current"), button -> this.router.openCompare(
                this,
                this.projectName,
                variant.headVersionId(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                variant.headVersionId()
        ));
        compareButton.active(headVersion != null);
        actions.child(compareButton);
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
