package io.github.luma.ui.screen;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
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

public final class CompareScreen extends LumaScreen {

    private static final int BLOCK_LIMIT = 8;
    private static final int MATERIAL_LIMIT = 8;

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
            "luma.status.compare_ready",
            false
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

        FlowLayout titleRow = LumaUi.actionRow();
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
            body.child(LumaUi.bottomSpacer());
            return;
        }

        body.child(this.summarySection());
        body.child(this.changedBlocksSection());
        body.child(this.materialsSection());
        body.child(LumaUi.bottomSpacer());
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

        boolean narrow = this.width < 760;
        FlowLayout inputs = narrow
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        inputs.gap(8);

        var leftBox = UIComponents.textBox(Sizing.fill(100), this.leftReference);
        leftBox.onChanged().subscribe(value -> this.leftReference = value);
        FlowLayout leftField = LumaUi.formField(
                Component.translatable("luma.compare.left"),
                Component.translatable("luma.compare.left_help"),
                leftBox
        );
        leftField.sizing(Sizing.fill(narrow ? 100 : 50), Sizing.content());
        inputs.child(leftField);

        var rightBox = UIComponents.textBox(Sizing.fill(100), this.rightReference);
        rightBox.onChanged().subscribe(value -> this.rightReference = value);
        FlowLayout rightField = LumaUi.formField(
                Component.translatable("luma.compare.right"),
                Component.translatable("luma.compare.right_help"),
                rightBox
        );
        rightField.sizing(Sizing.fill(narrow ? 100 : 50), Sizing.content());
        inputs.child(rightField);
        section.child(inputs);
        if (!this.state.leftResolvedVersionId().isBlank() || !this.state.rightResolvedVersionId().isBlank()) {
            section.child(LumaUi.chip(
                    Component.literal(this.displayResolved(this.state.leftResolvedVersionId())
                            + " -> "
                            + this.displayResolved(this.state.rightResolvedVersionId()))
            ));
        }

        if (!this.state.variants().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.compare.variants_hint",
                    String.join(", ", this.state.variants().stream().map(ProjectVariant::id).toList())
            )));
        }

        FlowLayout presets = this.quickPresetRow();
        if (!presets.children().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.compare.preset_help")));
            section.child(presets);
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
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.added"),
                Component.literal(Integer.toString(this.changeCount(ChangeType.ADDED)))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.removed"),
                Component.literal(Integer.toString(this.changeCount(ChangeType.REMOVED)))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.changed"),
                Component.literal(Integer.toString(this.changeCount(ChangeType.CHANGED)))
        ));
        section.child(stats);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent overlayButton = UIComponents.button(
                this.overlayButtonLabel(),
                button -> {
                    this.status = CompareOverlayRenderer.hasData()
                            ? this.controller.toggleOverlayVisibility()
                            : this.controller.showOverlay(this.state);
                    this.rebuild();
                }
        );
        overlayButton.active(!this.state.diff().changedBlocks().isEmpty() || CompareOverlayRenderer.hasData());
        actions.child(overlayButton);
        section.child(actions);
        return section;
    }

    private Component overlayButtonLabel() {
        if (!CompareOverlayRenderer.hasData()) {
            return Component.translatable("luma.action.highlight_compare");
        }
        return Component.translatable(CompareOverlayRenderer.visible()
                ? "luma.action.hide_highlight"
                : "luma.action.show_highlight");
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
            section.child(MaterialEntryView.row(
                    entry.blockId(),
                    Component.translatable(
                            "luma.compare.material_entry",
                            entry.blockId(),
                            entry.delta()
                    )
            ));
        }
        return section;
    }

    private FlowLayout quickPresetRow() {
        FlowLayout row = LumaUi.actionRow();
        String selectedVersionId = this.contextVersionId.isBlank() ? this.selectedVersionId() : this.contextVersionId;
        String parentVersionId = this.parentVersionId(selectedVersionId);
        if (!selectedVersionId.isBlank() && !parentVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.action.compare_with_parent"),
                    parentVersionId,
                    selectedVersionId
            ));
        }
        if (!selectedVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.action.compare_with_current"),
                    selectedVersionId,
                    CompareScreenController.CURRENT_WORLD_REFERENCE
            ));
        }
        String activeHeadVersionId = this.activeHeadVersionId();
        if (!activeHeadVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.compare.preset_active_branch"),
                    activeHeadVersionId,
                    CompareScreenController.CURRENT_WORLD_REFERENCE
            ));
        }
        return row;
    }

    private ButtonComponent pairedPresetButton(Component label, String leftValue, String rightValue) {
        ButtonComponent button = UIComponents.button(label, pressed -> {
            this.leftReference = leftValue;
            this.rightReference = rightValue;
            this.status = "luma.status.compare_ready";
            this.rebuild();
        });
        button.active(!leftValue.equalsIgnoreCase(this.leftReference) || !rightValue.equalsIgnoreCase(this.rightReference));
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

    private String displayResolved(String resolvedReference) {
        if (resolvedReference == null || resolvedReference.isBlank()) {
            return "?";
        }
        if (CompareScreenController.CURRENT_WORLD_REFERENCE.equals(resolvedReference)) {
            return Component.translatable("luma.compare.current_world_label").getString();
        }
        return resolvedReference;
    }

    private int changeCount(ChangeType type) {
        int count = 0;
        for (var entry : this.state.diff().changedBlocks()) {
            if (entry.changeType() == type) {
                count += 1;
            }
        }
        return count;
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
