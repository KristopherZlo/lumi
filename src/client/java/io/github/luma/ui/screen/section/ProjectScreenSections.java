package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

public final class ProjectScreenSections {

    private static final int RECENT_SAVE_LIMIT = 6;

    private final ProjectScreenController previewController;
    private final Actions actions;
    private final ProjectSaveCardView saveCardView;
    private final BranchHistoryVersions branchHistoryVersions = new BranchHistoryVersions();
    private OnboardingTour.SpotlightTarget onboardingSpotlightTarget = OnboardingTour.SpotlightTarget.NONE;
    private ButtonComponent onboardingSaveButton;
    private ButtonComponent onboardingChangesButton;

    public ProjectScreenSections(ProjectScreenController previewController, Actions actions) {
        this.previewController = Objects.requireNonNull(previewController, "previewController");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.saveCardView = new ProjectSaveCardView(this.previewController, this.actions);
    }

    public void prepareOnboardingSpotlight(OnboardingTour.SpotlightTarget target) {
        this.onboardingSpotlightTarget = target == null ? OnboardingTour.SpotlightTarget.NONE : target;
        this.onboardingSaveButton = null;
        this.onboardingChangesButton = null;
    }

    public UIComponent onboardingTargetComponent(OnboardingTour.SpotlightTarget target) {
        return switch (target == null ? OnboardingTour.SpotlightTarget.NONE : target) {
            case SAVE_BUILD -> this.onboardingSaveButton;
            case SEE_CHANGES -> this.onboardingChangesButton;
            case NONE -> null;
        };
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

        FlowLayout section = LumaUi.panel(Sizing.fill(100), Sizing.content());
        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout copy = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        copy.gap(2);
        copy.child(LumaUi.value(Component.translatable("luma.build.status_title")));
        copy.child(LumaUi.caption(Component.translatable(
                pending.isEmpty() ? "luma.build.status_clean" : "luma.build.status_dirty"
        )));
        header.child(copy);

        FlowLayout context = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        context.gap(4);
        context.child(LumaUi.chip(Component.translatable(
                "luma.build.current_idea",
                ProjectUiSupport.displayVariantName(activeVariant)
        )));
        context.child(LumaUi.chip(Component.translatable(
                "luma.build.current_place",
                ProjectUiSupport.dimensionLabel(model.state().project().dimensionId())
        )));
        header.child(context);
        section.child(header);

        ButtonComponent saveButton = LumaUi.primaryButton(
                Component.translatable("luma.action.save_build"),
                button -> this.actions.openSave()
        );
        saveButton.tooltip(Component.translatable("luma.action.save_build.tooltip"));
        this.onboardingSaveButton = saveButton;
        saveButton.active((!pending.isEmpty() && !operationActive)
                || this.onboardingSpotlightTarget == OnboardingTour.SpotlightTarget.SAVE_BUILD);
        if (!pending.isEmpty()) {
            FlowLayout stats = LumaUi.actionRow();
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_placed"), Component.literal("+" + pending.addedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_removed"), Component.literal("-" + pending.removedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
            section.child(stats);
        }
        FlowLayout actions = LumaUi.actionRow();
        actions.child(saveButton);
        ButtonComponent amendButton = LumaUi.button(
                Component.translatable("luma.action.amend_version"),
                button -> this.actions.openAmend(activeHead)
        );
        amendButton.tooltip(Component.translatable("luma.action.amend_version.tooltip"));
        amendButton.active(activeHead != null && !pending.isEmpty() && !operationActive);
        actions.child(amendButton);

        ButtonComponent changesButton = LumaUi.button(Component.translatable("luma.action.see_changes"), button -> this.actions.openCompare(
                activeHead == null ? "" : activeHead.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                activeHead == null ? "" : activeHead.id()
        ));
        changesButton.tooltip(Component.translatable("luma.action.see_changes.tooltip"));
        this.onboardingChangesButton = changesButton;
        changesButton.active(activeHead != null
                || this.onboardingSpotlightTarget == OnboardingTour.SpotlightTarget.SEE_CHANGES);
        actions.child(changesButton);
        ButtonComponent quickRollbackButton = LumaUi.button(
                Component.translatable("luma.action.quick_rollback"),
                button -> this.actions.quickRollback()
        );
        quickRollbackButton.tooltip(Component.translatable("luma.action.quick_rollback.tooltip"));
        quickRollbackButton.active(activeHead != null && !operationActive);
        actions.child(quickRollbackButton);
        ButtonComponent returnButton = LumaUi.button(
                Component.translatable("luma.action.return_before_restore"),
                button -> this.actions.returnBeforeRestore()
        );
        returnButton.tooltip(Component.translatable("luma.action.return_before_restore.tooltip"));
        returnButton.active(model.state().hasRestoreReturnPoint() && !operationActive);
        actions.child(returnButton);
        section.child(actions);

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
        List<BranchHistoryVersions.Entry> entries = selectedVariant == null
                ? List.of()
                : this.branchHistoryVersions.forVariant(model.state().versions(), model.state().variants(), selectedVariant);

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.build.recent_saves_title"),
                null
        );
        FlowLayout picker = this.variantPicker(model);
        if (!picker.children().isEmpty()) {
            section.child(picker);
        }

        if (entries.isEmpty()) {
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

        int limit = model.showAllSaves() ? entries.size() : Math.min(RECENT_SAVE_LIMIT, entries.size());
        for (int index = 0; index < limit; index++) {
            section.child(this.saveCard(model, entries.get(index)));
        }

        if (entries.size() > RECENT_SAVE_LIMIT) {
            FlowLayout historyActions = LumaUi.actionRow();
            historyActions.child(LumaUi.button(Component.translatable(
                    model.showAllSaves() ? "luma.action.show_recent_saves" : "luma.action.show_older_saves"
            ), button -> this.actions.toggleAllSaves()));
            section.child(historyActions);
        }
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
        if (model.state().variants().size() <= 1) {
            return picker;
        }
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

    private FlowLayout saveCard(Model model, BranchHistoryVersions.Entry entry) {
        boolean operationActive = model.state().operationSnapshot() != null && !model.state().operationSnapshot().terminal();
        return this.saveCardView.render(new ProjectSaveCardView.Model(
                model.projectName(),
                entry.version(),
                entry.variant(),
                entry.current(),
                operationActive,
                model.width()
        ));
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

    public record Model(
            String projectName,
            ProjectHomeViewState state,
            int width,
            String selectedVariantId,
            boolean showAllSaves,
            String pendingRestoreVariantId,
            String pendingRestoreVersionId,
            Optional<Bounds3i> lumiSelection
    ) {
        public Model {
            lumiSelection = lumiSelection == null ? Optional.empty() : lumiSelection;
        }
    }

    public interface Actions {

        void openSave();

        void openAmend(ProjectVersion activeHead);

        void openCompare(String leftReference, String rightReference, String contextVersionId);

        void openVariants();

        void openRecovery();

        void quickRollback();

        void returnBeforeRestore();

        void openSaveDetails(String versionId);

        void openBranchDialog(ProjectVersion version);

        void selectVariant(String variantId);

        void toggleAllSaves();

        void requestRestore(ProjectVariant variant, ProjectVersion version);
    }
}
