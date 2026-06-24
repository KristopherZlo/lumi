package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.UIComponent;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

public final class ProjectSaveCardView {

    private static final int PREVIEW_WIDTH = 96;
    private static final int PREVIEW_MIN_HEIGHT = 72;
    private static final int PREVIEW_MAX_HEIGHT = 96;

    private final PreviewFactory previewFactory;
    private final Actions actions;

    ProjectSaveCardView(ProjectScreenController previewController, ProjectScreenSections.Actions actions) {
        this(
                (projectName, version, width, minHeight, maxHeight) -> ProjectUiSupport.versionPreview(
                        previewController,
                        projectName,
                        version,
                        width,
                        minHeight,
                        maxHeight
                ),
                new Actions() {
                    @Override
                    public void openSaveDetails(String versionId) {
                        actions.openSaveDetails(versionId);
                    }

                    @Override
                    public void requestRestore(ProjectVariant variant, ProjectVersion version) {
                        actions.requestRestore(variant, version);
                    }

                    @Override
                    public void openBranchDialog(ProjectVersion version) {
                        actions.openBranchDialog(version);
                    }
                }
        );
    }

    public ProjectSaveCardView(ProjectScreenController previewController, Actions actions) {
        this(
                (projectName, version, width, minHeight, maxHeight) -> ProjectUiSupport.versionPreview(
                        previewController,
                        projectName,
                        version,
                        width,
                        minHeight,
                        maxHeight
                ),
                actions
        );
    }

    ProjectSaveCardView(PreviewFactory previewFactory, Actions actions) {
        this.previewFactory = Objects.requireNonNull(previewFactory, "previewFactory");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public FlowLayout render(Model model) {
        FlowLayout card = model.current()
                ? LumaUi.activeInsetPanel(Sizing.fill(100), Sizing.content())
                : LumaUi.insetPanel(Sizing.fill(100), Sizing.content());

        if (ProjectSaveCardLayout.placementFor(model.width()) == ProjectSaveCardLayout.Placement.INLINE_RIGHT) {
            card.child(this.wideRow(model));
        } else {
            card.child(this.narrowContent(model));
            card.child(this.actionRow(model));
        }
        return card;
    }

    private FlowLayout wideRow(Model model) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(8);
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(this.preview(model));
        row.child(this.text(model, Sizing.expand(100)));
        row.child(this.actionRow(model));
        return row;
    }

    private FlowLayout narrowContent(Model model) {
        FlowLayout content = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        content.gap(6);
        content.child(this.preview(model));
        content.child(this.text(model, Sizing.fill(100)));
        return content;
    }

    private UIComponent preview(Model model) {
        return this.previewFactory.preview(
                model.projectName(),
                model.version(),
                PREVIEW_WIDTH,
                PREVIEW_MIN_HEIGHT,
                PREVIEW_MAX_HEIGHT
        );
    }

    private FlowLayout text(Model model, Sizing horizontalSizing) {
        FlowLayout text = UIContainers.verticalFlow(horizontalSizing, Sizing.content());
        text.gap(3);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(model.version()))));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(model.version().author()),
                ProjectUiSupport.formatTimestamp(model.version().createdAt())
        )));
        text.child(LumaUi.caption(Component.translatable(
                "luma.build.save_card_summary",
                model.version().stats().changedBlocks()
        )));
        List<String> tags = ProjectVersionTags.from(model.version());
        if (!tags.isEmpty()) {
            FlowLayout tagRow = LumaUi.actionRow();
            for (String tag : tags) {
                tagRow.child(LumaUi.chip(Component.literal("#" + tag)));
            }
            text.child(tagRow);
        }
        if (model.current()) {
            FlowLayout meta = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
            meta.gap(4);
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
            text.child(meta);
        }
        return text;
    }

    private FlowLayout actionRow(Model model) {
        FlowLayout actions = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        actions.gap(4);
        actions.verticalAlignment(VerticalAlignment.CENTER);

        for (ProjectSaveCardLayout.ActionState actionState : ProjectSaveCardLayout.actions(
                model.versionVariant() != null,
                model.operationActive(),
                model.createVariantAction()
        )) {
            actions.child(this.actionButton(model, actionState));
        }
        return actions;
    }

    private ButtonComponent actionButton(Model model, ProjectSaveCardLayout.ActionState actionState) {
        ButtonComponent button = switch (actionState.action()) {
            case OPEN -> LumaUi.iconButton(
                    "folder-open",
                    Component.translatable("luma.action.open_save"),
                    pressed -> this.actions.openSaveDetails(model.version().id())
            );
            case RESTORE -> LumaUi.iconButton(
                    "rotate-ccw",
                    Component.translatable("luma.action.restore_this_save"),
                    pressed -> {
                        if (model.versionVariant() != null) {
                            this.actions.requestRestore(model.versionVariant(), model.version());
                        }
                    }
            );
            case CREATE_VARIANT -> LumaUi.iconButton(
                    "git-branch",
                    Component.translatable("luma.action.create_idea"),
                    pressed -> this.actions.openBranchDialog(model.version())
            );
        };
        button.active(actionState.active());
        return button;
    }

    public record Model(
            String projectName,
            ProjectVersion version,
            ProjectVariant versionVariant,
            boolean current,
            boolean operationActive,
            int width,
            boolean createVariantAction
    ) {
        public Model(
                String projectName,
                ProjectVersion version,
                ProjectVariant versionVariant,
                boolean current,
                boolean operationActive,
                int width
        ) {
            this(projectName, version, versionVariant, current, operationActive, width, true);
        }

        public Model {
            Objects.requireNonNull(projectName, "projectName");
            Objects.requireNonNull(version, "version");
        }
    }

    public interface Actions {

        void openSaveDetails(String versionId);

        void requestRestore(ProjectVariant variant, ProjectVersion version);

        void openBranchDialog(ProjectVersion version);
    }

    interface PreviewFactory {
        UIComponent preview(String projectName, ProjectVersion version, int width, int minHeight, int maxHeight);
    }
}
