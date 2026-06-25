package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.graph.CommitGraphComponent;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.onboarding.OnboardingTour;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
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
                selectedVariant == null ? null : Component.translatable(
                        "luma.build.recent_saves_help",
                        ProjectUiSupport.displayVariantName(selectedVariant)
                )
        );
        section.child(this.historyToolbar(model));
        if (model.historyGraphVisible()) {
            section.child(this.graphView(model, selectedVariant));
            return section;
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

        List<BranchHistoryVersions.Entry> visibleEntries = entries.stream()
                .filter(entry -> this.matchesTagFilter(entry.version(), model.historyTagFilter()))
                .toList();
        if (visibleEntries.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.history.tag_filter_empty")));
            return section;
        }

        BranchHistoryVersions.Entry latest = visibleEntries.stream()
                .max(Comparator.comparing(entry -> entry.version().createdAt()))
                .orElse(visibleEntries.getFirst());
        section.child(LumaUi.caption(Component.translatable("luma.history.current_badge")));
        section.child(this.saveCard(model, latest));

        List<BranchHistoryVersions.Entry> olderEntries = visibleEntries.stream()
                .filter(entry -> !entry.version().id().equals(latest.version().id()))
                .toList();
        if (!olderEntries.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.build.recent_saves_title")));
        }
        for (BranchHistoryVersions.Entry entry : olderEntries) {
            section.child(this.saveCard(model, entry));
        }
        return section;
    }

    private FlowLayout historyToolbar(Model model) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.verticalAlignment(VerticalAlignment.CENTER);
        FlowLayout filter = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        filter.child(this.tagFilter(model));
        row.child(filter);

        FlowLayout viewToggle = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        viewToggle.gap(4);

        ButtonComponent cards = LumaUi.iconButton(
                "view-cards",
                Component.translatable("luma.history.view_cards"),
                !model.historyGraphVisible(),
                button -> this.actions.setHistoryGraphVisible(false)
        );
        viewToggle.child(cards);

        ButtonComponent graph = LumaUi.iconButton(
                "view-graph",
                Component.translatable("luma.history.view_graph"),
                model.historyGraphVisible(),
                button -> this.actions.setHistoryGraphVisible(true)
        );
        viewToggle.child(graph);
        row.child(viewToggle);
        return row;
    }

    private FlowLayout tagFilter(Model model) {
        FlowLayout row = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        row.gap(2);
        TextBoxComponent input = UIComponents.textBox(Sizing.fixed(Math.min(180, Math.max(120, model.width() / 5))), model.historyTagFilter());
        input.setHint(Component.translatable("luma.history.tag_filter"));
        TagInputSupport.configure(input, model.historyTagFilter(), TagInputSupport.knownTags(model.state().versions()), false);
        String[] liveText = {model.historyTagFilter()};
        TagSuggestionComponent suggestions = new TagSuggestionComponent(
                () -> liveText[0],
                () -> TagInputSupport.knownTags(model.state().versions()),
                false,
                accepted -> {
                    liveText[0] = accepted;
                    this.actions.setHistoryTagFilter(accepted);
                    input.setValue(accepted);
                    input.setCursorPosition(accepted.length());
                    this.actions.refreshHistoryView();
                }
        );
        input.onChanged().subscribe(value -> {
            liveText[0] = TagInputSupport.limit(value);
            this.actions.setHistoryTagFilter(liveText[0]);
            suggestions.refresh();
        });
        input.focusLost().subscribe(this.actions::refreshHistoryView);
        row.child(input);
        row.child(suggestions);
        return row;
    }

    private FlowLayout graphView(Model model, ProjectVariant selectedVariant) {
        FlowLayout graph = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        if (selectedVariant == null) {
            graph.child(LumaUi.caption(Component.translatable("luma.history.empty")));
            return graph;
        }

        List<ProjectVersion> visibleVersions = model.state().versions().stream()
                .filter(version -> this.matchesTagFilter(version, model.historyTagFilter()))
                .toList();
        List<CommitGraphNode> nodes = CommitGraphLayout.build(
                visibleVersions,
                model.state().variants(),
                selectedVariant.id()
        );
        if (nodes.isEmpty()) {
            graph.child(LumaUi.caption(Component.translatable("luma.history.empty")));
            return graph;
        }
        graph.child(new CommitGraphComponent(
                nodes,
                model.state().variants(),
                versionId -> this.actions.openSaveDetails(versionId),
                model.projectName(),
                version -> this.previewController.resolvePreviewPath(model.projectName(), version.id())
        ));
        return graph;
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

    private FlowLayout saveCard(Model model, BranchHistoryVersions.Entry entry) {
        boolean operationActive = model.state().operationSnapshot() != null && !model.state().operationSnapshot().terminal();
        return this.saveCardView.render(new ProjectSaveCardView.Model(
                model.projectName(),
                entry.version(),
                entry.variant(),
                entry.current(),
                operationActive,
                model.width(),
                true,
                entry.version().id().equals(model.tagEditorVersionId()),
                model.tagEditorText(),
                TagInputSupport.knownTags(model.state().versions())
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

    private boolean matchesTagFilter(ProjectVersion version, String filter) {
        String needle = this.normalizedTagFilter(filter);
        return needle.isBlank() || ProjectVersionTags.from(version).stream()
                .anyMatch(tag -> tag.toLowerCase(java.util.Locale.ROOT).contains(needle));
    }

    private String normalizedTagFilter(String filter) {
        return filter == null ? "" : filter.trim().replaceFirst("^#+", "").toLowerCase(java.util.Locale.ROOT);
    }

    public record Model(
            String projectName,
            ProjectHomeViewState state,
            int width,
            String selectedVariantId,
            boolean historyGraphVisible,
            String historyTagFilter,
            String tagEditorVersionId,
            String tagEditorText,
            String pendingRestoreVariantId,
            String pendingRestoreVersionId,
            Optional<Bounds3i> lumiSelection
    ) {
        public Model {
            tagEditorVersionId = tagEditorVersionId == null ? "" : tagEditorVersionId;
            historyTagFilter = TagInputSupport.limit(historyTagFilter);
            tagEditorText = TagInputSupport.limit(tagEditorText);
            lumiSelection = lumiSelection == null ? Optional.empty() : lumiSelection;
        }
    }

    public interface Actions {

        void openSave();

        void openAmend(ProjectVersion activeHead);

        void openCompare(String leftReference, String rightReference, String contextVersionId);

        void openRecovery();

        void openSaveDetails(String versionId);

        void openBranchDialog(ProjectVersion version);

        void setHistoryGraphVisible(boolean visible);

        void setHistoryTagFilter(String filter);

        default void refreshHistoryView() {
        }

        void toggleTagEditor(ProjectVersion version);

        void updateTagEditor(String value);

        void saveTags(ProjectVersion version);

        void requestRestore(ProjectVariant variant, ProjectVersion version);

        default void bindTagInput(TextBoxComponent input) {
        }
    }
}
