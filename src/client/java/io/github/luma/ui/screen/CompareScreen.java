package io.github.luma.ui.screen;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
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

public final class CompareScreen extends BaseOwoScreen<FlowLayout> {

    private static final int BLOCK_LIMIT = 24;
    private static final int MATERIAL_LIMIT = 16;

    private final Screen parent;
    private final String projectName;
    private final String contextVersionId;
    private final Minecraft client = Minecraft.getInstance();
    private final CompareScreenController controller = new CompareScreenController();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private CompareViewState state = new CompareViewState(
            List.of(),
            List.of(),
            "",
            "",
            "",
            "",
            "",
            null,
            List.of(),
            "luma.status.compare_ready"
    );
    private String leftReference;
    private String rightReference;
    private String status = "luma.status.compare_ready";

    public CompareScreen(Screen parent, String projectName, String leftReference, String rightReference) {
        this(parent, projectName, leftReference, rightReference, "");
    }

    public CompareScreen(Screen parent, String projectName, String leftReference, String rightReference, String contextVersionId) {
        super(Component.translatable("luma.screen.compare.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.leftReference = leftReference == null ? "" : leftReference;
        this.rightReference = rightReference == null ? "" : rightReference;
        this.contextVersionId = contextVersionId == null ? "" : contextVersionId;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.leftReference, this.rightReference, this.status);

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        FlowLayout titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleRow.gap(8);
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.compare.title", this.projectName)));
        if (!this.state.activeVariantId().isBlank()) {
            titleRow.child(LumaUi.chip(Component.translatable("luma.dashboard.active_branch", this.state.activeVariantId())));
        }
        frame.child(titleRow);
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        body.child(this.referenceSection());
        if (this.state.diff() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.compare.empty_title"),
                    Component.translatable("luma.compare.empty")
            ));
            return;
        }

        body.child(this.summarySection());
        body.child(this.changedBlocksSection());
        body.child(this.materialsSection());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout referenceSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.setup_title"),
                Component.translatable("luma.compare.setup_help")
        );

        var leftBox = UIComponents.textBox(Sizing.fill(100), this.leftReference);
        leftBox.onChanged().subscribe(value -> this.leftReference = value);
        section.child(LumaUi.formField(
                Component.translatable("luma.compare.left"),
                Component.translatable("luma.compare.left_help"),
                leftBox
        ));
        section.child(this.resolvedRow(
                Component.translatable("luma.compare.left_resolved"),
                this.leftReference,
                this.state.leftResolvedVersionId()
        ));
        section.child(this.presetCard(true));

        var rightBox = UIComponents.textBox(Sizing.fill(100), this.rightReference);
        rightBox.onChanged().subscribe(value -> this.rightReference = value);
        section.child(LumaUi.formField(
                Component.translatable("luma.compare.right"),
                Component.translatable("luma.compare.right_help"),
                rightBox
        ));
        section.child(this.resolvedRow(
                Component.translatable("luma.compare.right_resolved"),
                this.rightReference,
                this.state.rightResolvedVersionId()
        ));
        section.child(this.presetCard(false));

        if (!this.state.variants().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.compare.variants_hint",
                    String.join(", ", this.state.variants().stream().map(ProjectVariant::id).toList())
            )));
        }

        FlowLayout actions = LumaUi.actionRow();
        actions.child(UIComponents.button(Component.translatable("luma.action.compare"), button -> {
            this.status = "luma.status.compare_ready";
            this.rebuild();
        }));
        section.child(actions);
        return section;
    }

    private FlowLayout summarySection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.result_title"),
                Component.translatable("luma.compare.summary",
                        this.displayResolved(this.state.leftResolvedVersionId()),
                        this.displayResolved(this.state.rightResolvedVersionId()),
                        this.state.diff().changedBlockCount(),
                        this.state.diff().changedChunks())
        );

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_blocks"),
                Component.literal(Integer.toString(this.state.diff().changedBlockCount()))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.history.commit_chunks"),
                Component.literal(Integer.toString(this.state.diff().changedChunks()))
        ));
        section.child(stats);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent overlayButton = UIComponents.button(
                Component.translatable(
                        CompareOverlayRenderer.active()
                                ? "luma.action.hide_highlight"
                                : "luma.action.highlight_compare"
                ),
                button -> {
                    this.status = CompareOverlayRenderer.active()
                            ? this.controller.clearOverlay()
                            : this.controller.showOverlay(this.state);
                    this.rebuild();
                }
        );
        overlayButton.active(!this.state.diff().changedBlocks().isEmpty() || CompareOverlayRenderer.active());
        actions.child(overlayButton);
        section.child(actions);
        return section;
    }

    private FlowLayout changedBlocksSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.blocks_title"),
                Component.translatable("luma.compare.blocks_help", Math.min(BLOCK_LIMIT, this.state.diff().changedBlocks().size()))
        );

        int limit = Math.min(BLOCK_LIMIT, this.state.diff().changedBlocks().size());
        for (int index = 0; index < limit; index++) {
            var entry = this.state.diff().changedBlocks().get(index);
            section.child(LumaUi.caption(Component.translatable(
                    "luma.compare.block_entry",
                    entry.pos().x(),
                    entry.pos().y(),
                    entry.pos().z(),
                    Component.translatable(this.changeTypeKey(entry.changeType()))
            )));
        }

        if (this.state.diff().changedBlocks().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.changes.empty")));
        }
        return section;
    }

    private FlowLayout materialsSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.materials_title"),
                Component.translatable("luma.compare.materials_help", Math.min(MATERIAL_LIMIT, this.state.materialDelta().size()))
        );

        if (this.state.materialDelta().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.materials.empty")));
            return section;
        }

        int limit = Math.min(MATERIAL_LIMIT, this.state.materialDelta().size());
        for (int index = 0; index < limit; index++) {
            var entry = this.state.materialDelta().get(index);
            section.child(LumaUi.caption(Component.translatable(
                    "luma.compare.material_entry",
                    entry.blockId(),
                    entry.delta()
            )));
        }
        return section;
    }

    private FlowLayout resolvedRow(Component label, String rawReference, String resolvedReference) {
        FlowLayout row = LumaUi.insetSection(label, this.resolvedText(rawReference, resolvedReference));
        return row;
    }

    private FlowLayout presetCard(boolean leftSide) {
        FlowLayout card = LumaUi.insetSection(
                Component.translatable(leftSide ? "luma.compare.left_preset_title" : "luma.compare.right_preset_title"),
                Component.translatable("luma.compare.preset_help")
        );

        FlowLayout firstRow = LumaUi.actionRow();
        String selectedVersionId = this.contextVersionId.isBlank() ? this.selectedVersionId() : this.contextVersionId;
        if (!selectedVersionId.isBlank()) {
            firstRow.child(this.presetButton(
                    leftSide,
                    Component.translatable("luma.compare.preset_selected_version"),
                    selectedVersionId
            ));
        }
        String parentVersionId = this.parentVersionId(selectedVersionId);
        if (!parentVersionId.isBlank()) {
            firstRow.child(this.presetButton(
                    leftSide,
                    Component.translatable("luma.compare.preset_parent_version"),
                    parentVersionId
            ));
        }
        if (firstRow.children().size() > 0) {
            card.child(firstRow);
        }

        FlowLayout secondRow = LumaUi.actionRow();
        String activeHeadVersionId = this.activeHeadVersionId();
        if (!activeHeadVersionId.isBlank()) {
            secondRow.child(this.presetButton(
                    leftSide,
                    Component.translatable("luma.compare.preset_active_branch"),
                    activeHeadVersionId
            ));
        }
        secondRow.child(this.presetButton(
                leftSide,
                Component.translatable("luma.compare.preset_current_world"),
                CompareScreenController.CURRENT_WORLD_REFERENCE
        ));
        card.child(secondRow);
        return card;
    }

    private ButtonComponent presetButton(boolean leftSide, Component label, String value) {
        ButtonComponent button = UIComponents.button(label, pressed -> {
            if (leftSide) {
                this.leftReference = value;
            } else {
                this.rightReference = value;
            }
            this.status = "luma.status.compare_ready";
            this.rebuild();
        });

        String currentValue = leftSide ? this.leftReference : this.rightReference;
        button.active(!value.equalsIgnoreCase(currentValue));
        return button;
    }

    private String selectedVersionId() {
        if (this.state.rightResolvedVersionId() != null
                && !this.state.rightResolvedVersionId().isBlank()
                && !CompareScreenController.CURRENT_WORLD_REFERENCE.equals(this.state.rightResolvedVersionId())) {
            return this.state.rightResolvedVersionId();
        }
        return "";
    }

    private String parentVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        for (ProjectVersion version : this.state.versions()) {
            if (version.id().equals(versionId)) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    private String activeHeadVersionId() {
        for (ProjectVariant variant : this.state.variants()) {
            if (variant.id().equals(this.state.activeVariantId())) {
                return variant.headVersionId();
            }
        }
        return "";
    }

    private Component resolvedText(String rawReference, String resolvedReference) {
        if (resolvedReference == null || resolvedReference.isBlank()) {
            if (rawReference == null || rawReference.isBlank()) {
                return Component.translatable("luma.compare.resolved_waiting");
            }
            return Component.translatable("luma.compare.resolved_missing", rawReference);
        }

        if (CompareScreenController.CURRENT_WORLD_REFERENCE.equals(resolvedReference)
                || CompareScreenController.isCurrentWorldReference(rawReference)) {
            return Component.translatable("luma.compare.resolved_current_world");
        }

        return Component.translatable("luma.compare.resolved_version", resolvedReference);
    }

    private String displayResolved(String resolvedReference) {
        if (CompareScreenController.CURRENT_WORLD_REFERENCE.equals(resolvedReference)) {
            return Component.translatable("luma.compare.current_world_label").getString();
        }
        return resolvedReference;
    }

    private String changeTypeKey(ChangeType type) {
        return switch (type) {
            case ADDED -> "luma.change_type.added";
            case REMOVED -> "luma.change_type.removed";
            case CHANGED -> "luma.change_type.changed";
        };
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
