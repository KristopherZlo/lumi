package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class ProjectScreenSections {

    private static final int RECENT_SAVE_LIMIT = 6;

    private final ProjectScreenController previewController;
    private final Actions actions;

    public ProjectScreenSections(ProjectScreenController previewController, Actions actions) {
        this.previewController = Objects.requireNonNull(previewController, "previewController");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout buildSection(Model model) {
        PendingChangeSummary pending = model.state().pendingChanges();
        ProjectVersion activeHead = ProjectUiSupport.activeHead(
                model.state().project(),
                model.state().variants(),
                model.state().versions()
        );
        ProjectVariant activeVariant = ProjectUiSupport.variantFor(
                model.state().variants(),
                model.state().project().activeVariantId()
        );
        boolean operationActive = this.operationActive(model);

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.build.status_title"),
                Component.translatable(pending.isEmpty() ? "luma.build.status_clean" : "luma.build.status_dirty")
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(
                "luma.build.current_idea",
                ProjectUiSupport.displayVariantName(activeVariant)
        )));
        meta.child(LumaUi.chip(Component.translatable(
                "luma.build.current_place",
                ProjectUiSupport.dimensionLabel(model.state().project().dimensionId())
        )));
        section.child(meta);

        if (!pending.isEmpty()) {
            FlowLayout stats = LumaUi.actionRow();
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_placed"), Component.literal("+" + pending.addedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_removed"), Component.literal("-" + pending.removedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
            section.child(stats);
        }

        ButtonComponent saveButton = LumaUi.primaryButton(
                Component.translatable("luma.action.save_build"),
                button -> this.actions.openSave()
        );
        saveButton.active(!pending.isEmpty() && !operationActive);
        FlowLayout primary = LumaUi.actionRow();
        primary.child(saveButton);
        if (pending.isEmpty()) {
            primary.child(LumaUi.caption(Component.translatable("luma.build.save_disabled_help")));
        }
        section.child(primary);

        FlowLayout secondary = LumaUi.actionRow();
        ButtonComponent changesButton = LumaUi.button(Component.translatable("luma.action.see_changes"), button -> this.actions.openCompare(
                activeHead == null ? "" : activeHead.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                activeHead == null ? "" : activeHead.id()
        ));
        changesButton.active(activeHead != null);
        secondary.child(changesButton);
        secondary.child(LumaUi.button(Component.translatable("luma.action.ideas"), button -> this.actions.openVariants()));
        secondary.child(LumaUi.button(Component.translatable("luma.action.more"), button -> this.actions.openMore()));
        section.child(secondary);

        if (model.state().hasRecoveryDraft()) {
            FlowLayout recovery = LumaUi.insetSection(
                    Component.translatable("luma.recovery.found_title"),
                    Component.translatable("luma.recovery.found_help")
            );
            FlowLayout recoveryActions = LumaUi.actionRow();
            recoveryActions.child(LumaUi.primaryButton(
                    Component.translatable("luma.action.review_recovered_work"),
                    button -> this.actions.openRecovery()
            ));
            recovery.child(recoveryActions);
            section.child(recovery);
        }
        if (model.state().operationSnapshot() != null) {
            section.child(this.operationSection(model));
        }
        return section;
    }

    public FlowLayout historySection(Model model) {
        ProjectVariant selectedVariant = this.selectedVariant(model);
        List<ProjectVersion> versions = selectedVariant == null ? List.of() : this.variantVersions(model, selectedVariant.id());

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.build.recent_saves_title"),
                Component.translatable(
                        "luma.build.recent_saves_help",
                        selectedVariant == null
                                ? Component.translatable("luma.variant.empty")
                                : Component.literal(ProjectUiSupport.displayVariantName(selectedVariant))
                )
        );
        section.child(LumaUi.caption(Component.translatable("luma.build.idea_picker_help")));
        section.child(this.variantPicker(model));

        if (versions.isEmpty()) {
            FlowLayout empty = LumaUi.emptyState(
                    Component.translatable("luma.build.no_saves_title"),
                    Component.translatable("luma.build.no_saves_help")
            );
            FlowLayout emptyActions = LumaUi.actionRow();
            ButtonComponent saveButton = LumaUi.primaryButton(
                    Component.translatable("luma.action.save_build"),
                    button -> this.actions.openSave()
            );
            saveButton.active(!model.state().pendingChanges().isEmpty() && !this.operationActive(model));
            emptyActions.child(saveButton);
            empty.child(emptyActions);
            section.child(empty);
            return section;
        }

        int limit = model.showAllSaves() ? versions.size() : Math.min(RECENT_SAVE_LIMIT, versions.size());
        for (int index = 0; index < limit; index++) {
            section.child(this.saveCard(model, versions.get(index)));
        }

        if (versions.size() > RECENT_SAVE_LIMIT) {
            FlowLayout historyActions = LumaUi.actionRow();
            historyActions.child(LumaUi.button(Component.translatable(
                    model.showAllSaves() ? "luma.action.show_recent_saves" : "luma.action.show_older_saves"
            ), button -> this.actions.toggleAllSaves()));
            section.child(historyActions);
        }
        return section;
    }

    public FlowLayout initialRestoreConfirmationSection(Model model) {
        if (model.pendingRestoreVersionId().isBlank()) {
            return null;
        }

        ProjectVersion version = ProjectUiSupport.versionFor(model.state().versions(), model.pendingRestoreVersionId());
        ProjectVariant variant = ProjectUiSupport.variantFor(model.state().variants(), model.pendingRestoreVariantId());
        if (version == null || variant == null) {
            this.actions.clearPendingRestore();
            return null;
        }

        boolean rootRestore = version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT;
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.restore.confirm_title", ProjectUiSupport.displayMessage(version)),
                Component.translatable("luma.restore.confirm_help")
        );
        if (model.state().project().settings().safetySnapshotBeforeRestore()) {
            section.child(LumaUi.caption(Component.translatable("luma.restore.confirm_safety")));
        }
        if (rootRestore) {
            section.child(LumaUi.danger(Component.translatable("luma.restore.initial_confirm_warning")));
        }
        section.child(LumaUi.caption(Component.translatable(
                "luma.restore.confirm_target",
                ProjectUiSupport.displayVariantName(variant),
                ProjectUiSupport.displayMessage(version)
        )));

        FlowLayout restoreActions = LumaUi.actionRow();
        restoreActions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.actions.cancelRestore()));
        ButtonComponent confirmButton = LumaUi.primaryButton(
                Component.translatable("luma.action.restore"),
                button -> this.actions.confirmRestore(variant, version)
        );
        confirmButton.active(model.state().operationSnapshot() == null || model.state().operationSnapshot().terminal());
        restoreActions.child(confirmButton);
        section.child(restoreActions);
        return section;
    }

    private FlowLayout operationSection(Model model) {
        var operation = model.state().operationSnapshot();
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

    private FlowLayout variantPicker(Model model) {
        FlowLayout picker = LumaUi.actionRow();
        for (ProjectVariant variant : this.sortedVariants(model)) {
            ButtonComponent button = LumaUi.button(
                    Component.literal(ProjectUiSupport.displayVariantName(variant)),
                    pressed -> this.actions.selectVariant(variant.id())
            );
            button.active(!variant.id().equals(model.selectedVariantId()));
            picker.child(button);
        }
        return picker;
    }

    private FlowLayout saveCard(Model model, ProjectVersion version) {
        ProjectVariant versionVariant = ProjectUiSupport.variantFor(model.state().variants(), version.variantId());
        boolean current = ProjectUiSupport.isVariantHead(model.state().variants(), version);
        boolean operationActive = model.state().operationSnapshot() != null && !model.state().operationSnapshot().terminal();

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout hero = model.width() < 860
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        hero.gap(8);
        hero.child(ProjectUiSupport.versionPreview(
                this.previewController,
                model.projectName(),
                version,
                96,
                72,
                96
        ));

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(4);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        text.child(LumaUi.caption(Component.translatable(
                "luma.build.save_card_summary",
                version.stats().changedBlocks()
        )));

        if (current) {
            FlowLayout meta = LumaUi.actionRow();
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
            text.child(meta);
        }
        hero.child(text);
        card.child(hero);

        FlowLayout saveActions = LumaUi.actionRow();
        saveActions.child(LumaUi.button(Component.translatable("luma.action.open_save"), button -> this.actions.openSaveDetails(version.id())));
        ButtonComponent restoreButton = LumaUi.button(Component.translatable("luma.action.restore_this_save"), button -> {
            if (versionVariant != null) {
                this.actions.requestRestore(versionVariant, version);
            }
        });
        restoreButton.active(versionVariant != null && !operationActive);
        saveActions.child(restoreButton);
        card.child(saveActions);
        return card;
    }

    private boolean operationActive(Model model) {
        return ScreenOperationStateSupport.blocksMutationActions(model.state().operationSnapshot());
    }

    private ProjectVariant selectedVariant(Model model) {
        ProjectVariant selected = ProjectUiSupport.variantFor(model.state().variants(), model.selectedVariantId());
        if (selected != null) {
            return selected;
        }
        if (model.state().project() == null) {
            return null;
        }
        return ProjectUiSupport.variantFor(model.state().variants(), model.state().project().activeVariantId());
    }

    private List<ProjectVariant> sortedVariants(Model model) {
        return model.state().variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(model.state().project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private List<ProjectVersion> variantVersions(Model model, String variantId) {
        return model.state().versions().stream()
                .filter(version -> variantId.equals(version.variantId()))
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
    }

    public record Model(
            String projectName,
            ProjectHomeViewState state,
            int width,
            String selectedVariantId,
            boolean showAllSaves,
            String pendingRestoreVariantId,
            String pendingRestoreVersionId
    ) {
    }

    public interface Actions {

        void openSave();

        void openCompare(String leftReference, String rightReference, String contextVersionId);

        void openVariants();

        void openMore();

        void openRecovery();

        void openSaveDetails(String versionId);

        void selectVariant(String variantId);

        void toggleAllSaves();

        void requestRestore(ProjectVariant variant, ProjectVersion version);

        void cancelRestore();

        void confirmRestore(ProjectVariant variant, ProjectVersion version);

        void clearPendingRestore();
    }
}
