package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
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

    private static final Identifier COMPARE_ICON = Identifier.fromNamespaceAndPath(
            "lumi",
            "textures/gui/icons/see-changes.png"
    );

    private final BranchHistoryVersions branchHistoryVersions = new BranchHistoryVersions();
    private final Actions actions;

    public ProjectCompareScreenSections(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout pickerSection(Model model) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.compare.pick_title"),
                Component.translatable("luma.compare.pick_help")
        );

        FlowLayout columns = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        columns.gap(8);
        columns.verticalAlignment(VerticalAlignment.CENTER);
        columns.child(this.historyColumn(model, Side.LEFT));
        columns.child(this.divider(model));
        columns.child(this.historyColumn(model, Side.RIGHT));
        section.child(columns);
        section.child(this.compareActionRow(model));
        return section;
    }

    private FlowLayout historyColumn(Model model, Side side) {
        FlowLayout column = LumaUi.insetPanel(Sizing.expand(50), Sizing.content());
        column.child(LumaUi.value(Component.translatable(side == Side.LEFT
                ? "luma.compare.left_column"
                : "luma.compare.right_column")));
        column.child(this.branchSelector(model, side));

        List<BranchHistoryVersions.Entry> entries = this.entriesFor(model, this.selectedVariantId(model, side));
        if (entries.isEmpty()) {
            column.child(LumaUi.caption(Component.translatable("luma.history.empty")));
            return column;
        }
        for (BranchHistoryVersions.Entry entry : entries) {
            column.child(this.saveOptionCard(model, side, entry));
        }
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

        FlowLayout title = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        title.gap(4);
        title.verticalAlignment(VerticalAlignment.CENTER);
        title.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
        title.child(UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1)));
        if (entry.current()) {
            title.child(LumaUi.chip(Component.translatable("luma.project.active_head_badge")));
        }
        card.child(title);
        card.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        card.child(LumaUi.caption(Component.translatable(
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
        card.child(actions);
        return card;
    }

    private FlowLayout divider(Model model) {
        FlowLayout divider = UIContainers.verticalFlow(
                Sizing.fixed(28),
                Sizing.fixed(Math.max(160, Math.min(420, model.height() - 150)))
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
            ProjectHomeViewState state,
            int height,
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
