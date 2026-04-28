package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.CompareViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class CompareScreenSections {

    private static final int BLOCK_LIMIT = 8;
    private static final int MATERIAL_LIMIT = 8;

    private final Actions actions;

    public CompareScreenSections(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout referenceSection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.manual_title"),
                Component.translatable("luma.compare.manual_help")
        );

        boolean narrow = model.width() < 760;
        FlowLayout inputs = narrow
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        inputs.gap(8);

        var leftBox = UIComponents.textBox(Sizing.fill(100), model.leftReference());
        leftBox.onChanged().subscribe(this.actions::updateLeftReference);
        FlowLayout leftField = LumaUi.formField(
                Component.translatable("luma.compare.left"),
                Component.translatable("luma.compare.left_help"),
                leftBox
        );
        leftField.sizing(narrow ? Sizing.fill(100) : Sizing.expand(50), Sizing.content());
        inputs.child(leftField);

        var rightBox = UIComponents.textBox(Sizing.fill(100), model.rightReference());
        rightBox.onChanged().subscribe(this.actions::updateRightReference);
        FlowLayout rightField = LumaUi.formField(
                Component.translatable("luma.compare.right"),
                Component.translatable("luma.compare.right_help"),
                rightBox
        );
        rightField.sizing(narrow ? Sizing.fill(100) : Sizing.expand(50), Sizing.content());
        inputs.child(rightField);
        section.child(inputs);

        FlowLayout actionsRow = LumaUi.actionRow();
        actionsRow.child(LumaUi.primaryButton(Component.translatable("luma.action.compare"), button -> this.actions.runCompare()));
        section.child(actionsRow);

        FlowLayout presets = this.quickPresetRow(model);
        if (!presets.children().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.compare.preset_help")));
            section.child(presets);
        }
        return section;
    }

    public FlowLayout summarySection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                this.compareTitle(model.state()),
                Component.translatable(
                        "luma.compare.summary_short",
                        model.state().diff().changedBlockCount()
                )
        );

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.added"),
                Component.literal(Integer.toString(ProjectUiSupport.changeCount(model.state().diff(), ChangeType.ADDED)))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.removed"),
                Component.literal(Integer.toString(ProjectUiSupport.changeCount(model.state().diff(), ChangeType.REMOVED)))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.change_type.changed"),
                Component.literal(Integer.toString(ProjectUiSupport.changeCount(model.state().diff(), ChangeType.CHANGED)))
        ));
        section.child(stats);

        FlowLayout actionsRow = LumaUi.actionRow();
        ButtonComponent overlayButton = LumaUi.button(this.overlayButtonLabel(), button -> this.actions.toggleOverlay());
        overlayButton.active(!model.state().diff().changedBlocks().isEmpty() || CompareOverlayRenderer.hasData());
        actionsRow.child(overlayButton);

        actionsRow.child(LumaUi.button(Component.translatable(
                model.showMoreDetails() ? "luma.action.hide_tools" : "luma.compare.more_details"
        ), button -> this.actions.toggleMoreDetails()));
        section.child(actionsRow);
        return section;
    }

    public FlowLayout moreDetailsSection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.more_title"),
                Component.translatable("luma.compare.more_help")
        );
        if (!model.showMoreDetails()) {
            return section;
        }

        FlowLayout expanded = LumaUi.revealGroup();
        expanded.child(this.materialsSection(model));
        expanded.child(this.positionsSection(model));
        expanded.child(LumaUi.caption(Component.translatable(
                "luma.compare.summary",
                this.displayResolved(model.state(), model.state().leftResolvedVersionId()),
                this.displayResolved(model.state(), model.state().rightResolvedVersionId()),
                model.state().diff().changedBlockCount(),
                model.state().diff().changedChunks()
        )));
        expanded.child(LumaUi.caption(Component.translatable(
                "luma.compare.left_resolved",
                this.displayResolved(model.state(), model.state().leftResolvedVersionId())
        )));
        expanded.child(LumaUi.caption(Component.translatable(
                "luma.compare.right_resolved",
                this.displayResolved(model.state(), model.state().rightResolvedVersionId())
        )));
        expanded.child(LumaUi.caption(Component.translatable("luma.compare.raw_chunks", model.state().diff().changedChunks())));
        FlowLayout manual = LumaUi.actionRow();
        manual.child(LumaUi.button(Component.translatable(
                model.showManualCompare() ? "luma.action.hide_manual_compare" : "luma.action.manual_compare"
        ), button -> this.actions.toggleManualCompare()));
        expanded.child(manual);
        if (model.showManualCompare()) {
            FlowLayout manualExpanded = LumaUi.revealGroup();
            manualExpanded.child(this.referenceSection(model));
            expanded.child(manualExpanded);
        }
        expanded.child(LumaUi.caption(Component.translatable("luma.compare.hotkey_hint")));
        section.child(expanded);
        return section;
    }

    public Component compareTitle(CompareViewState state) {
        if (state.diff() == null) {
            return Component.translatable("luma.screen.changes.title");
        }
        return Component.translatable(
                "luma.compare.title_with_refs",
                this.displayResolved(state, state.leftResolvedVersionId()),
                this.displayResolved(state, state.rightResolvedVersionId())
        );
    }

    private FlowLayout materialsSection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.materials_title"),
                Component.translatable("luma.compare.materials_help", Math.min(MATERIAL_LIMIT, model.state().materialDelta().size()))
        );

        if (model.state().materialDelta().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.materials.empty")));
            return section;
        }

        int limit = Math.min(MATERIAL_LIMIT, model.state().materialDelta().size());
        for (int index = 0; index < limit; index++) {
            var entry = model.state().materialDelta().get(index);
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

    private FlowLayout positionsSection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.blocks_title"),
                Component.translatable("luma.compare.blocks_help", Math.min(BLOCK_LIMIT, model.state().diff().changedBlocks().size()))
        );

        int limit = Math.min(BLOCK_LIMIT, model.state().diff().changedBlocks().size());
        for (int index = 0; index < limit; index++) {
            var entry = model.state().diff().changedBlocks().get(index);
            section.child(LumaUi.caption(Component.translatable(
                    "luma.compare.block_entry",
                    entry.pos().x(),
                    entry.pos().y(),
                    entry.pos().z(),
                    Component.translatable(this.changeTypeKey(entry.changeType()))
            )));
        }
        if (limit == 0) {
            section.child(LumaUi.caption(Component.translatable("luma.changes.empty")));
        }
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

    private FlowLayout quickPresetRow(Model model) {
        FlowLayout row = LumaUi.actionRow();
        String selectedVersionId = model.contextVersionId().isBlank()
                ? this.selectedVersionId(model.state())
                : model.contextVersionId();
        String parentVersionId = this.parentVersionId(model.state(), selectedVersionId);
        if (!selectedVersionId.isBlank() && !parentVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.action.compare_with_parent"),
                    parentVersionId,
                    selectedVersionId,
                    model
            ));
        }
        if (!selectedVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.action.compare_with_current"),
                    selectedVersionId,
                    CompareScreenController.CURRENT_WORLD_REFERENCE,
                    model
            ));
        }
        String activeHeadVersionId = this.activeHeadVersionId(model.state());
        if (!activeHeadVersionId.isBlank()) {
            row.child(this.pairedPresetButton(
                    Component.translatable("luma.compare.preset_active_branch"),
                    activeHeadVersionId,
                    CompareScreenController.CURRENT_WORLD_REFERENCE,
                    model
            ));
        }
        return row;
    }

    private ButtonComponent pairedPresetButton(Component label, String leftValue, String rightValue, Model model) {
        ButtonComponent button = LumaUi.button(label, pressed -> this.actions.applyPreset(leftValue, rightValue));
        button.active(!leftValue.equalsIgnoreCase(model.leftReference()) || !rightValue.equalsIgnoreCase(model.rightReference()));
        return button;
    }

    private ProjectVersion versionFor(CompareViewState state, String versionId) {
        for (ProjectVersion version : state.versions()) {
            if (version.id().equals(versionId)) {
                return version;
            }
        }
        return null;
    }

    private String selectedVersionId(CompareViewState state) {
        if (state.rightResolvedVersionId() != null
                && !state.rightResolvedVersionId().isBlank()
                && !CompareScreenController.CURRENT_WORLD_REFERENCE.equals(state.rightResolvedVersionId())) {
            return state.rightResolvedVersionId();
        }
        return "";
    }

    private String parentVersionId(CompareViewState state, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        for (ProjectVersion version : state.versions()) {
            if (version.id().equals(versionId)) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    private String activeHeadVersionId(CompareViewState state) {
        for (var variant : state.variants()) {
            if (variant.id().equals(state.activeVariantId())) {
                return variant.headVersionId();
            }
        }
        return "";
    }

    private String displayResolved(CompareViewState state, String resolvedReference) {
        if (resolvedReference == null || resolvedReference.isBlank()) {
            return "?";
        }
        if (CompareScreenController.CURRENT_WORLD_REFERENCE.equals(resolvedReference)) {
            return Component.translatable("luma.compare.current_world_label").getString();
        }
        ProjectVersion version = this.versionFor(state, resolvedReference);
        if (version != null) {
            return ProjectUiSupport.displayMessage(version);
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

    public record Model(
            CompareViewState state,
            int width,
            String leftReference,
            String rightReference,
            String contextVersionId,
            boolean showMoreDetails,
            boolean showManualCompare
    ) {
    }

    public interface Actions {

        void updateLeftReference(String value);

        void updateRightReference(String value);

        void runCompare();

        void toggleOverlay();

        void toggleMoreDetails();

        void toggleManualCompare();

        void applyPreset(String leftReference, String rightReference);
    }
}
