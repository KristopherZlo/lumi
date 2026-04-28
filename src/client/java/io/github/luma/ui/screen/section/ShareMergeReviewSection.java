package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.MergeConflictResolution;
import io.github.luma.domain.model.MergeConflictZone;
import io.github.luma.domain.model.MergeConflictZoneResolution;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.ShareViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class ShareMergeReviewSection {

    private final Actions actions;

    public ShareMergeReviewSection(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout pendingSection(Model model) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.import_export.combine_review_title"),
                Component.translatable("luma.share.merge_preview_loading")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.share.target_variant",
                ProjectUiSupport.displayVariantName(model.state().variants(), model.selectedTargetVariantId())
        )));
        return section;
    }

    public FlowLayout reviewSection(Model model) {
        ProjectVersion commonAncestor = ProjectUiSupport.versionFor(
                model.state().versions(),
                model.mergePlan().commonAncestorVersionId()
        );
        FlowLayout section = LumaUi.insetSection(
                Component.translatable(
                        "luma.import_export.combine_review_title_with_names",
                        this.importedVariantLabel(model),
                        ProjectUiSupport.displayVariantName(model.state().variants(), model.selectedTargetVariantId())
                ),
                Component.translatable(
                        "luma.share.merge_ancestor",
                        commonAncestor == null
                                ? ProjectUiSupport.safeText(model.mergePlan().commonAncestorVersionId())
                                : ProjectUiSupport.displayMessage(commonAncestor)
                )
        );

        section.child(LumaUi.caption(Component.translatable(
                "luma.share.target_variant",
                ProjectUiSupport.displayVariantName(model.state().variants(), model.selectedTargetVariantId())
        )));
        section.child(this.variantButtons(model));

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.share.source_changes"), Component.literal(Integer.toString(model.mergePlan().sourceChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.target_changes"), Component.literal(Integer.toString(model.mergePlan().targetChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.merge_changes"), Component.literal(Integer.toString(model.mergePlan().mergeBlockCount()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.conflicts"), Component.literal(Integer.toString(model.mergePlan().conflictPositions().size()))));
        section.child(stats);

        if (model.mergePlan().hasConflicts()) {
            section.child(LumaUi.danger(Component.translatable(
                    "luma.share.merge_conflicts",
                    model.mergePlan().conflictPositions().size(),
                    model.mergePlan().conflictChunkCount()
            )));
            FlowLayout conflictOverlayActions = LumaUi.actionRow();
            conflictOverlayActions.child(LumaUi.button(
                    Component.translatable("luma.action.show_all_conflicts"),
                    button -> this.actions.showAllConflicts(model.mergePlan())
            ));
            section.child(conflictOverlayActions);
            for (MergeConflictZone zone : model.mergePlan().conflictZones()) {
                section.child(this.conflictZoneCard(model, zone));
            }
        } else if (model.mergePlan().mergeChanges().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_no_changes")));
        }

        if (CompareOverlayRenderer.hasData()) {
            FlowLayout overlayActions = LumaUi.actionRow();
            overlayActions.child(LumaUi.button(
                    Component.translatable("luma.action.hide_highlight"),
                    button -> this.actions.clearOverlay()
            ));
            section.child(overlayActions);
        }

        List<MergeConflictZoneResolution> resolutions = this.conflictZoneResolutions(model);
        FlowLayout actionsRow = LumaUi.actionRow();
        ButtonComponent mergeButton = LumaUi.primaryButton(
                Component.translatable("luma.action.apply_combine"),
                button -> this.actions.applyMerge(resolutions)
        );
        mergeButton.active(model.mergePlan().canApply(resolutions) && !model.operationActive());
        actionsRow.child(mergeButton);
        section.child(actionsRow);

        boolean allZonesResolved = model.mergePlan().conflictZones().stream()
                .allMatch(zone -> model.conflictResolutions().containsKey(zone.id()));
        if (model.mergePlan().canApply(resolutions)) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.share.merge_ready",
                    model.mergePlan().effectiveMergeBlockCount(resolutions)
            )));
        } else if (allZonesResolved && model.mergePlan().effectiveMergeBlockCount(resolutions) == 0) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_no_changes")));
        } else if (model.mergePlan().hasConflicts()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_unresolved_help")));
        }
        return section;
    }

    private FlowLayout conflictZoneCard(Model model, MergeConflictZone zone) {
        MergeConflictResolution resolution = model.conflictResolutions().get(zone.id());

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.translatable("luma.share.zone_title", this.zoneLabel(zone))));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.zone_summary",
                zone.chunkCount(),
                zone.blockCount()
        )));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.zone_bounds",
                zone.bounds().min().x(),
                zone.bounds().min().y(),
                zone.bounds().min().z(),
                zone.bounds().max().x(),
                zone.bounds().max().y(),
                zone.bounds().max().z()
        )));
        for (var pos : zone.samplePositions(3)) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.share.zone_position",
                    pos.x(),
                    pos.y(),
                    pos.z()
            )));
        }
        card.child(LumaUi.caption(Component.translatable(this.zoneStatusKey(resolution))));

        FlowLayout actionsRow = LumaUi.actionRow();
        ButtonComponent keepLocal = LumaUi.button(
                Component.translatable("luma.action.keep_mine"),
                button -> this.actions.setZoneResolution(zone.id(), MergeConflictResolution.KEEP_LOCAL)
        );
        keepLocal.active(resolution != MergeConflictResolution.KEEP_LOCAL);
        actionsRow.child(keepLocal);

        ButtonComponent useImported = LumaUi.button(
                Component.translatable("luma.action.use_imported"),
                button -> this.actions.setZoneResolution(zone.id(), MergeConflictResolution.USE_IMPORTED)
        );
        useImported.active(resolution != MergeConflictResolution.USE_IMPORTED);
        actionsRow.child(useImported);

        ButtonComponent skip = LumaUi.button(
                Component.translatable("luma.action.skip_for_now"),
                button -> this.actions.clearZoneResolution(zone.id())
        );
        skip.active(resolution != null);
        actionsRow.child(skip);

        actionsRow.child(LumaUi.button(
                Component.translatable("luma.action.show_highlight"),
                button -> this.actions.showZoneHighlight(zone)
        ));
        card.child(actionsRow);
        return card;
    }

    private FlowLayout variantButtons(Model model) {
        FlowLayout row = LumaUi.actionRow();
        for (ProjectVariant variant : this.sortedVariants(model)) {
            ButtonComponent button = LumaUi.button(
                    Component.literal(ProjectUiSupport.displayVariantName(variant)),
                    pressed -> this.actions.selectTargetVariant(variant.id())
            );
            button.active(!variant.id().equals(model.selectedTargetVariantId()));
            row.child(button);
        }
        return row;
    }

    private List<ProjectVariant> sortedVariants(Model model) {
        return model.state().variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(model.state().project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private List<MergeConflictZoneResolution> conflictZoneResolutions(Model model) {
        return model.conflictResolutions().entrySet().stream()
                .map(entry -> new MergeConflictZoneResolution(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String zoneStatusKey(MergeConflictResolution resolution) {
        if (resolution == null) {
            return "luma.share.zone_status_unresolved";
        }
        return switch (resolution) {
            case KEEP_LOCAL -> "luma.share.zone_status_keep_local";
            case USE_IMPORTED -> "luma.share.zone_status_use_imported";
        };
    }

    private String zoneLabel(MergeConflictZone zone) {
        return zone.id().startsWith("zone-") ? zone.id().substring("zone-".length()) : zone.id();
    }

    private String importedVariantLabel(Model model) {
        if (model.selectedImportedVariantName() != null && !model.selectedImportedVariantName().isBlank()) {
            return model.selectedImportedVariantName();
        }
        return ProjectUiSupport.safeText(model.selectedImportedVariantId());
    }

    public record Model(
            ShareViewState state,
            VariantMergePlan mergePlan,
            String selectedTargetVariantId,
            String selectedImportedVariantId,
            String selectedImportedVariantName,
            Map<String, MergeConflictResolution> conflictResolutions,
            boolean operationActive
    ) {
    }

    public interface Actions {

        void selectTargetVariant(String variantId);

        void showAllConflicts(VariantMergePlan mergePlan);

        void clearOverlay();

        void setZoneResolution(String zoneId, MergeConflictResolution resolution);

        void clearZoneResolution(String zoneId);

        void showZoneHighlight(MergeConflictZone zone);

        void applyMerge(List<MergeConflictZoneResolution> resolutions);
    }
}
