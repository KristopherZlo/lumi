package io.github.luma.ui.screen;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.toolkit.LdLib2ReflectiveUi;
import io.github.luma.ui.toolkit.LdLib2ReflectiveUi.TextTone;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LdLib2ProjectHomeScreenFactory {

    private static final int RECENT_SAVE_LIMIT = 6;

    private final LdLib2ReflectiveUi ui;
    private final Screen parent;
    private final String projectName;
    private final String statusKey;
    private final Minecraft client = Minecraft.getInstance();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private ProjectHomeViewState state;
    private String selectedVariantId;

    private LdLib2ProjectHomeScreenFactory(
            LdLib2ReflectiveUi ui,
            Screen parent,
            String projectName,
            String selectedVariantId,
            String statusKey
    ) {
        this.ui = ui;
        this.parent = parent;
        this.projectName = projectName;
        this.selectedVariantId = selectedVariantId == null ? "" : selectedVariantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
    }

    static Optional<Screen> create(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        Optional<LdLib2ReflectiveUi> runtime = LdLib2ReflectiveUi.create(LdLib2ProjectHomeScreenFactory.class.getClassLoader());
        if (runtime.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new LdLib2ProjectHomeScreenFactory(
                    runtime.get(),
                    parent,
                    projectName,
                    selectedVariantId,
                    statusKey
            ).createScreen());
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("LDLib2 project home screen failed; falling back to owo-lib", exception);
            return Optional.empty();
        }
    }

    private Screen createScreen() {
        this.state = this.stateController.loadState(this.projectName, this.statusKey, false);
        this.ensureSelectedVariant();

        Object root = this.ui.element("lumi-root", "lumi-root");
        this.ui.layout(root, layout -> layout.widthPercent(100).heightPercent(100).paddingAll(8));

        Object window = this.ui.panel("lumi-window");
        this.ui.layout(window, layout -> layout.widthPercent(100).heightPercent(100).row().gapAll(0));
        this.ui.addChild(root, window);

        if (this.state.project() == null) {
            this.ui.addChild(window, this.unavailablePanel());
            return this.ui.screen(root, Component.translatable("luma.screen.project.title", this.projectName));
        }

        Object sidebar = this.sidebar();
        Object content = this.content();
        this.ui.addChild(window, sidebar);
        this.ui.addChild(window, content);
        return this.ui.screen(root, Component.translatable("luma.screen.project.title", this.projectName));
    }

    private Object unavailablePanel() {
        Object panel = this.ui.panel("lumi-unavailable");
        this.ui.layout(panel, layout -> layout.widthPercent(100).heightPercent(100).paddingAll(6).gapAll(5));
        this.ui.addChild(panel, this.ui.label("lumi-unavailable-title",
                Component.translatable("luma.project.unavailable"),
                TextTone.VALUE
        ));
        this.ui.addChild(panel, this.ui.label("lumi-unavailable-help",
                Component.translatable("luma.status.project_failed"),
                TextTone.MUTED
        ));
        this.ui.addChild(panel, this.ui.button("lumi-back", Component.translatable("luma.action.back"), () -> this.client.setScreen(this.parent)));
        return panel;
    }

    private Object sidebar() {
        Object sidebar = this.ui.element("lumi-sidebar", "panel_bg", "lumi-sidebar");
        this.ui.layout(sidebar, layout -> layout.width(this.client.getWindow().getGuiScaledWidth() < 720 ? 126 : 158)
                .heightPercent(100)
                .paddingAll(7)
                .gapAll(5)
                .flexShrink(0));

        this.ui.addChild(sidebar, this.ui.label("lumi-brand", Component.literal("Lumi"), TextTone.TITLE));
        this.ui.addChild(sidebar, this.ui.label("lumi-mode", Component.translatable("luma.window.mode"), TextTone.MUTED));
        this.ui.addChild(sidebar, this.chip("lumi-place", Component.translatable(
                "luma.simple.current_place",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        )));
        this.ui.addChild(sidebar, this.chip("lumi-idea", Component.translatable(
                "luma.simple.current_idea",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));
        this.ui.addChild(sidebar, this.ui.button("lumi-back", Component.translatable("luma.action.back"), () -> this.client.setScreen(this.parent)));
        this.ui.addChild(sidebar, this.ui.button("lumi-more", Component.translatable("luma.action.more"), () -> this.router.openMore(
                this.currentScreen(),
                this.projectName
        )));
        return sidebar;
    }

    private Object content() {
        Object content = this.ui.element("lumi-content");
        this.ui.layout(content, layout -> layout.widthPercent(100).heightPercent(100).paddingAll(7).gapAll(6).flex(1));
        this.ui.addChild(content, this.titleBar());
        this.ui.addChild(content, this.statusBanner());

        Object scroller = this.ui.scroller("lumi-history");
        this.ui.addScrollChild(scroller, this.buildSection());
        this.ui.addScrollChild(scroller, this.historySection());
        this.ui.addChild(content, scroller);
        return content;
    }

    private Object titleBar() {
        Object titleBar = this.ui.panel("lumi-title-bar");
        this.ui.layout(titleBar, layout -> layout.widthPercent(100).paddingAll(6).gapAll(2));
        this.ui.addChild(titleBar, this.ui.label("lumi-title",
                Component.translatable("luma.simple.workspace_title", this.projectName),
                TextTone.TITLE
        ));
        this.ui.addChild(titleBar, this.ui.label("lumi-title-help",
                Component.translatable("luma.window.home_help"),
                TextTone.MUTED
        ));
        return titleBar;
    }

    private Object statusBanner() {
        Object banner = this.ui.panel("lumi-status");
        this.ui.layout(banner, layout -> layout.widthPercent(100).paddingAll(5));
        this.ui.addChild(banner, this.ui.label("lumi-status-text", this.bannerText(), TextTone.ACCENT));
        return banner;
    }

    private Object buildSection() {
        PendingChangeSummary pending = this.state.pendingChanges();
        ProjectVersion activeHead = ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
        ProjectVariant activeVariant = ProjectUiSupport.variantFor(this.state.variants(), this.state.project().activeVariantId());
        boolean operationActive = this.operationActive();

        Object section = this.section("lumi-build-section",
                Component.translatable("luma.build.status_title"),
                Component.translatable(pending.isEmpty() ? "luma.build.status_clean" : "luma.build.status_dirty")
        );

        Object meta = this.row("lumi-build-meta");
        this.ui.addChild(meta, this.chip("lumi-current-idea", Component.translatable(
                "luma.build.current_idea",
                ProjectUiSupport.displayVariantName(activeVariant)
        )));
        this.ui.addChild(meta, this.chip("lumi-current-place", Component.translatable(
                "luma.build.current_place",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        )));
        this.ui.addChild(section, meta);

        if (!pending.isEmpty()) {
            Object stats = this.row("lumi-build-stats");
            this.ui.addChild(stats, this.chip("lumi-added", Component.translatable("luma.build.blocks_placed", "+" + pending.addedBlocks())));
            this.ui.addChild(stats, this.chip("lumi-removed", Component.translatable("luma.build.blocks_removed", "-" + pending.removedBlocks())));
            this.ui.addChild(stats, this.chip("lumi-changed", Component.translatable("luma.build.blocks_changed", pending.changedBlocks())));
            this.ui.addChild(section, stats);
        }

        Object saveButton = this.ui.button("lumi-save-build", Component.translatable("luma.action.save_build"), () -> this.router.openSave(
                this.currentScreen(),
                this.projectName
        ));
        this.ui.active(saveButton, !pending.isEmpty() && !operationActive);
        this.ui.addChild(section, saveButton);
        if (pending.isEmpty()) {
            this.ui.addChild(section, this.ui.label("lumi-save-help", Component.translatable("luma.build.save_disabled_help"), TextTone.MUTED));
        }

        Object secondary = this.row("lumi-secondary-actions");
        Object changesButton = this.ui.button("lumi-see-changes", Component.translatable("luma.action.see_changes"), () -> this.router.openCompare(
                this.currentScreen(),
                this.projectName,
                activeHead == null ? "" : activeHead.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                activeHead == null ? "" : activeHead.id()
        ));
        this.ui.active(changesButton, activeHead != null);
        this.ui.addChild(secondary, changesButton);
        this.ui.addChild(secondary, this.ui.button("lumi-ideas", Component.translatable("luma.action.ideas"), () -> this.router.openVariants(
                this.currentScreen(),
                this.projectName
        )));
        this.ui.addChild(section, secondary);

        if (this.state.operationSnapshot() != null) {
            this.ui.addChild(section, this.operationSection());
        }
        return section;
    }

    private Object operationSection() {
        var operation = this.state.operationSnapshot();
        Object section = this.section("lumi-operation",
                Component.translatable("luma.project.operation_title"),
                Component.literal(OperationProgressPresenter.progressSummary(operation))
        );
        this.ui.addChild(section, this.ui.label("lumi-operation-stage", Component.translatable(
                "luma.project.operation_stage",
                operation.stage().name().toLowerCase(java.util.Locale.ROOT)
        ), TextTone.MUTED));
        this.ui.addChild(section, this.ui.label("lumi-operation-percent", Component.translatable(
                "luma.project.operation_percent_label",
                OperationProgressPresenter.displayPercent(operation)
        ), TextTone.MUTED));
        return section;
    }

    private Object historySection() {
        ProjectVariant selectedVariant = this.selectedVariant();
        List<ProjectVersion> versions = selectedVariant == null ? List.of() : this.variantVersions(selectedVariant.id());
        Object section = this.section("lumi-history-section",
                Component.translatable("luma.build.recent_saves_title"),
                Component.translatable(
                        "luma.build.recent_saves_help",
                        selectedVariant == null
                                ? Component.translatable("luma.variant.empty")
                                : Component.literal(ProjectUiSupport.displayVariantName(selectedVariant))
                )
        );
        this.ui.addChild(section, this.variantPicker());

        if (versions.isEmpty()) {
            this.ui.addChild(section, this.ui.label("lumi-no-saves-title",
                    Component.translatable("luma.build.no_saves_title"),
                    TextTone.VALUE
            ));
            this.ui.addChild(section, this.ui.label("lumi-no-saves-help",
                    Component.translatable("luma.build.no_saves_help"),
                    TextTone.MUTED
            ));
            return section;
        }

        int limit = Math.min(RECENT_SAVE_LIMIT, versions.size());
        for (int index = 0; index < limit; index++) {
            this.ui.addChild(section, this.saveCard(versions.get(index)));
        }
        return section;
    }

    private Object variantPicker() {
        Object picker = this.row("lumi-variant-picker");
        for (ProjectVariant variant : this.sortedVariants()) {
            Object button = this.ui.button("lumi-variant-" + variant.id(), Component.literal(ProjectUiSupport.displayVariantName(variant)), () -> {
                this.client.setScreen(ProjectScreenFactory.create(this.parent, this.projectName, variant.id(), "luma.status.project_ready"));
            });
            this.ui.active(button, !variant.id().equals(this.selectedVariantId));
            this.ui.addChild(picker, button);
        }
        return picker;
    }

    private Object saveCard(ProjectVersion version) {
        Object card = this.section("lumi-save-" + version.id(),
                Component.literal(ProjectUiSupport.displayMessage(version)),
                Component.translatable(
                        "luma.history.version_meta",
                        ProjectUiSupport.safeText(version.author()),
                        ProjectUiSupport.formatTimestamp(version.createdAt())
                )
        );
        this.ui.addChild(card, this.chip("lumi-save-kind-" + version.id(), Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))));

        Object actions = this.row("lumi-save-actions-" + version.id());
        this.ui.addChild(actions, this.ui.button("lumi-open-save-" + version.id(), Component.translatable("luma.action.open_save"), () -> this.router.openSaveDetails(
                this.currentScreen(),
                this.projectName,
                version.id()
        )));
        this.ui.addChild(actions, this.ui.button("lumi-compare-save-" + version.id(), Component.translatable("luma.action.see_changes"), () -> this.router.openCompare(
                this.currentScreen(),
                this.projectName,
                version.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                version.id()
        )));
        this.ui.addChild(card, actions);
        return card;
    }

    private Object section(String id, Component title, Component subtitle) {
        Object section = this.ui.panel(id);
        this.ui.layout(section, layout -> layout.widthPercent(100).paddingAll(5).gapAll(4));
        this.ui.addChild(section, this.ui.label(id + "-title", title, TextTone.VALUE));
        if (subtitle != null) {
            this.ui.addChild(section, this.ui.label(id + "-subtitle", subtitle, TextTone.MUTED));
        }
        return section;
    }

    private Object chip(String id, Component text) {
        Object chip = this.ui.panel(id);
        this.ui.layout(chip, layout -> layout.widthPercent(100).paddingAll(3));
        this.ui.addChild(chip, this.ui.label(id + "-text", text, TextTone.MUTED));
        return chip;
    }

    private Object row(String id) {
        Object row = this.ui.element(id);
        this.ui.layout(row, layout -> layout.widthPercent(100).row().gapAll(4));
        return row;
    }

    private Screen currentScreen() {
        return this.client.screen;
    }

    private boolean operationActive() {
        return ScreenOperationStateSupport.blocksMutationActions(this.state.operationSnapshot());
    }

    private Component bannerText() {
        return ScreenOperationStateSupport.bannerText(this.state.status(), this.state.operationSnapshot(), "luma.status.project_ready");
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

    private ProjectVariant selectedVariant() {
        ProjectVariant selected = ProjectUiSupport.variantFor(this.state.variants(), this.selectedVariantId);
        if (selected != null) {
            return selected;
        }
        if (this.state.project() == null) {
            return null;
        }
        return ProjectUiSupport.variantFor(this.state.variants(), this.state.project().activeVariantId());
    }

    private List<ProjectVariant> sortedVariants() {
        return this.state.variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private List<ProjectVersion> variantVersions(String variantId) {
        return this.state.versions().stream()
                .filter(version -> variantId.equals(version.variantId()))
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
    }
}
