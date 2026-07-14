package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.ShareViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import java.util.Comparator;
import java.util.List;
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
        section.child(stats);

        if (model.mergePlan().safetyReport().requiresTrustedConfirmation()) {
            section.child(LumaUi.danger(Component.translatable("luma.share.package_safety_warning")));
            if (!model.mergePlan().safetyReport().dangerousBlockEntityTypes().isEmpty()) {
                section.child(LumaUi.caption(Component.translatable(
                        "luma.share.package_safety_block_entities",
                        String.join(", ", model.mergePlan().safetyReport().dangerousBlockEntityTypes())
                )));
            }
            if (!model.mergePlan().safetyReport().dangerousEntityTypes().isEmpty()) {
                section.child(LumaUi.caption(Component.translatable(
                        "luma.share.package_safety_entities",
                        String.join(", ", model.mergePlan().safetyReport().dangerousEntityTypes())
                )));
            }
            FlowLayout trustRow = LumaUi.actionRow();
            var trustedCheckbox = UIComponents.checkbox(Component.translatable("luma.share.trusted_package_confirm"));
            trustedCheckbox.checked(model.trustedPackageConfirmed());
            trustedCheckbox.onChanged(this.actions::setTrustedPackageConfirmed);
            trustRow.child(trustedCheckbox);
            section.child(trustRow);
        }

        if (model.mergePlan().mergeChangeCount() == 0) {
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

        FlowLayout actionsRow = LumaUi.actionRow();
        ButtonComponent mergeButton = LumaUi.primaryButton(
                Component.translatable("luma.action.apply_combine"),
                button -> this.actions.applyMerge()
        );
        mergeButton.active(model.mergePlan().canApply()
                && !model.operationActive()
                && (!model.mergePlan().safetyReport().requiresTrustedConfirmation() || model.trustedPackageConfirmed()));
        actionsRow.child(mergeButton);
        section.child(actionsRow);

        if (model.mergePlan().canApply()) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.share.merge_ready",
                    model.mergePlan().mergeChangeCount()
            )));
        }
        return section;
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
            boolean operationActive,
            boolean trustedPackageConfirmed
    ) {
    }

    public interface Actions {

        void selectTargetVariant(String variantId);

        void clearOverlay();

        void setTrustedPackageConfirmed(boolean trusted);

        void applyMerge();
    }
}
