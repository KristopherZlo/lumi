package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ProjectCompareScreenSections {

    private static final int PREVIEW_WIDTH = 72;
    private static final int PREVIEW_MIN_HEIGHT = 48;
    private static final int PREVIEW_MAX_HEIGHT = 64;
    private static final Identifier COMPARE_ICON = Identifier.fromNamespaceAndPath(
            "lumi",
            "textures/gui/icons/see-changes.png"
    );

    private final BranchHistoryVersions branchHistoryVersions = new BranchHistoryVersions();
    private final ProjectScreenController previewController;
    private final Actions actions;

    public ProjectCompareScreenSections(ProjectScreenController previewController, Actions actions) {
        this.previewController = Objects.requireNonNull(previewController, "previewController");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout pickerSection(Model model) {
        FlowLayout section = LumaUi.panel(Sizing.fill(100), Sizing.expand(100));
        section.child(LumaUi.value(Component.translatable("luma.compare.pick_title")));
        section.child(LumaUi.caption(Component.translatable("luma.compare.pick_help")));

        FlowLayout columns = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.expand(100));
        columns.gap(8);
        columns.child(this.historyColumn(model, Side.LEFT));
        columns.child(this.divider());
        columns.child(this.historyColumn(model, Side.RIGHT));
        section.child(columns);
        section.child(this.compareActionRow(model));
        return section;
    }

    private FlowLayout historyColumn(Model model, Side side) {
        FlowLayout column = LumaUi.insetPanel(Sizing.expand(50), Sizing.fill(100));
        column.child(LumaUi.value(Component.translatable(side == Side.LEFT
                ? "luma.compare.left_column"
                : "luma.compare.right_column")));
        column.child(this.branchSelector(model, side));

        FlowLayout history = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        history.gap(4);
        List<BranchHistoryVersions.Entry> entries = this.entriesFor(model, this.selectedVariantId(model, side));
        if (entries.isEmpty()) {
            history.child(LumaUi.caption(Component.translatable("luma.history.empty")));
            column.child(LumaUi.screenScroll(Sizing.fill(100), Sizing.expand(100), history));
            return column;
        }
        for (BranchHistoryVersions.Entry entry : entries) {
            history.child(this.saveOptionCard(model, side, entry));
        }
        column.child(LumaUi.screenScroll(Sizing.fill(100), Sizing.expand(100), history));
        return column;
    }

    private FlowLayout branchSelector(Model model, Side side) {
        FlowLayout row = LumaUi.actionRow();
        for (ProjectVariant variant : model.state().variants()) {
            ButtonComponent button = LumaUi.button(
                    Component.literal(ProjectUiSupport.displayVariantName(variant)),
                    pressed -> this.actions.selectVariant(side, variant.id())
            );
            button.active(!variant.id().equals(this.selectedVariantId(model, side)));
            row.child(button);
        }
        return row;
    }

    private FlowLayout saveOptionCard(Model model, Side side, BranchHistoryVersions.Entry entry) {
        ProjectVersion version = entry.version();
        boolean selected = version.id().equals(this.selectedVersionId(model, side));
        FlowLayout card = selected
                ? LumaUi.activeInsetPanel(Sizing.fill(100), Sizing.content())
                : LumaUi.insetPanel(Sizing.fill(100), Sizing.content());

        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(6);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(ProjectUiSupport.versionPreview(
                this.previewController,
                model.projectName(),
                version,
                PREVIEW_WIDTH,
                PREVIEW_MIN_HEIGHT,
                PREVIEW_MAX_HEIGHT
        ));

        FlowLayout details = UIContainers.verticalFlow(Sizing.expand(100), Sizing.content());
        details.gap(3);
        FlowLayout title = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        title.gap(4);
        title.verticalAlignment(VerticalAlignment.CENTER);
        title.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
        title.child(UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1)));
        if (entry.current()) {
            title.child(LumaUi.chip(Component.translatable("luma.project.active_head_badge")));
        }
        details.child(title);
        details.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        details.child(LumaUi.caption(Component.translatable(
                "luma.build.save_card_summary",
                version.stats() == null ? 0 : version.stats().changedBlocks()
        )));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent select = LumaUi.button(
                Component.translatable(selected ? "luma.compare.selected_save" : "luma.compare.select_save"),
                pressed -> this.actions.selectVersion(side, version.id())
        );
        select.active(!selected);
        actions.child(select);
        details.child(actions);
        row.child(details);
        card.child(row);
        return card;
    }

    private FlowLayout divider() {
        FlowLayout divider = UIContainers.verticalFlow(
                Sizing.fixed(28),
                Sizing.fill(100)
        );
        divider.horizontalAlignment(HorizontalAlignment.CENTER);
        divider.verticalAlignment(VerticalAlignment.CENTER);
        divider.gap(6);
        divider.child(this.dividerLine());
        var icon = UIComponents.texture(COMPARE_ICON, 0, 0, 16, 16, 16, 16);
        icon.blend(true);
        icon.sizing(Sizing.fixed(16), Sizing.fixed(16));
        divider.child(icon);
        divider.child(this.dividerLine());
        return divider;
    }

    private FlowLayout dividerLine() {
        FlowLayout line = UIContainers.verticalFlow(Sizing.fixed(1), Sizing.expand(50));
        line.surface(Surface.flat(0xFF343238));
        return line;
    }

    private FlowLayout compareActionRow(Model model) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.child(UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1)));
        ButtonComponent compare = LumaUi.primaryButton(Component.translatable("luma.action.compare"), button -> this.actions.runCompare());
        compare.active(this.canCompare(model));
        row.child(compare);
        return row;
    }

    private boolean canCompare(Model model) {
        return !model.selectedLeftVersionId().isBlank()
                && !model.selectedRightVersionId().isBlank()
                && !model.selectedLeftVersionId().equals(model.selectedRightVersionId());
    }

    private List<BranchHistoryVersions.Entry> entriesFor(Model model, String variantId) {
        ProjectVariant variant = ProjectUiSupport.variantFor(model.state().variants(), variantId);
        return variant == null
                ? List.of()
                : this.branchHistoryVersions.forVariant(model.state().versions(), model.state().variants(), variant);
    }

    private String selectedVariantId(Model model, Side side) {
        return side == Side.LEFT ? model.selectedLeftVariantId() : model.selectedRightVariantId();
    }

    private String selectedVersionId(Model model, Side side) {
        return side == Side.LEFT ? model.selectedLeftVersionId() : model.selectedRightVersionId();
    }

    public record Model(
            String projectName,
            ProjectHomeViewState state,
            String selectedLeftVariantId,
            String selectedRightVariantId,
            String selectedLeftVersionId,
            String selectedRightVersionId
    ) {
    }

    public interface Actions {

        void selectVariant(Side side, String variantId);

        void selectVersion(Side side, String versionId);

        void runCompare();
    }

    public enum Side {
        LEFT,
        RIGHT
    }
}
