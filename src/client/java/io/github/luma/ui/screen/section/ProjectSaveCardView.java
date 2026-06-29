package io.github.luma.ui.screen.section;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.controller.ProjectScreenController;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
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
    private UIComponent onboardingRestoreButton;

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

                    @Override
                    public void toggleTagEditor(ProjectVersion version) {
                        actions.toggleTagEditor(version);
                    }

                    @Override
                    public void updateTagEditor(String value) {
                        actions.updateTagEditor(value);
                    }

                    @Override
                    public void saveTags(ProjectVersion version) {
                        actions.saveTags(version);
                    }

                    @Override
                    public void bindTagInput(TextBoxComponent input) {
                        actions.bindTagInput(input);
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
        if (model.onboardingRestoreTarget()) {
            this.onboardingRestoreButton = null;
        }
        FlowLayout card = model.current()
                ? LumaUi.activeInsetPanel(Sizing.fill(100), Sizing.content())
                : LumaUi.insetPanel(Sizing.fill(100), Sizing.content());

        if (ProjectSaveCardLayout.placementFor(model.width()) == ProjectSaveCardLayout.Placement.INLINE_RIGHT) {
            card.child(this.wideRow(model));
        } else {
            card.child(this.narrowContent(model));
            card.child(this.actionRow(model));
        }
        card.child(this.tagRow(model));
        if (model.tagEditorVisible()) {
            card.child(this.tagEditor(model));
        }
        return card;
    }

    UIComponent onboardingRestoreButton() {
        return this.onboardingRestoreButton;
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
        text.child(this.zoneTitle(model));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(model.version().author()),
                ProjectUiSupport.formatTimestamp(model.version().createdAt())
        )));
        text.child(LumaUi.caption(Component.translatable(
                "luma.build.save_card_summary",
                model.version().stats().changedBlocks()
        )));
        return text;
    }

    private FlowLayout zoneTitle(Model model) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.verticalAlignment(VerticalAlignment.CENTER);
        if (model.zoneColor() != null) {
            row.child(this.zoneColorDot(model.zoneColor()));
        }
        row.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(model.version()))));
        return row;
    }

    private FlowLayout zoneColorDot(int color) {
        FlowLayout dot = UIContainers.verticalFlow(Sizing.fixed(7), Sizing.fixed(7));
        dot.surface(Surface.flat(0xFF000000 | color));
        return dot;
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

    private FlowLayout tagRow(Model model) {
        FlowLayout row = LumaUi.actionRow();
        for (String tag : ProjectVersionTags.from(model.version())) {
            row.child(LumaUi.caption(Component.literal("#" + tag)));
        }
        row.child(LumaUi.iconButton(
                "tags",
                Component.translatable("luma.action.edit_tags"),
                button -> this.actions.toggleTagEditor(model.version())
        ));
        return row;
    }

    private FlowLayout tagEditor(Model model) {
        FlowLayout editor = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        editor.gap(2);
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        row.verticalAlignment(VerticalAlignment.CENTER);
        TextBoxComponent input = UIComponents.textBox(
                Sizing.expand(100),
                model.tagEditorText()
        );
        input.setHint(Component.translatable("luma.history.tags_input"));
        TagInputSupport.configure(input, model.tagEditorText(), model.knownTags(), true);
        this.actions.bindTagInput(input);
        String[] liveText = {model.tagEditorText()};
        TagSuggestionComponent suggestions = new TagSuggestionComponent(
                () -> liveText[0],
                model::knownTags,
                true,
                accepted -> {
                    liveText[0] = accepted;
                    this.actions.updateTagEditor(accepted);
                    input.setValue(accepted);
                    input.setCursorPosition(accepted.length());
                }
        );
        input.onChanged().subscribe(value -> {
            liveText[0] = TagInputSupport.limit(value);
            this.actions.updateTagEditor(liveText[0]);
            suggestions.refresh();
        });
        row.child(input);
        ButtonComponent save = LumaUi.iconButton(
                "save",
                Component.translatable("luma.action.save_tags"),
                button -> this.actions.saveTags(model.version())
        );
        save.margins(Insets.none());
        row.child(save);
        editor.child(row);
        editor.child(suggestions);
        return editor;
    }

    private ButtonComponent actionButton(Model model, ProjectSaveCardLayout.ActionState actionState) {
        ButtonComponent button = switch (actionState.action()) {
            case OPEN -> LumaUi.iconButton(
                    "folder",
                    Component.translatable("luma.action.open_save"),
                    pressed -> this.actions.openSaveDetails(model.version().id())
            );
            case RESTORE -> LumaUi.iconButton(
                    "rollback",
                    Component.translatable("luma.action.restore_this_save"),
                    pressed -> {
                        if (model.versionVariant() != null) {
                            this.actions.requestRestore(model.versionVariant(), model.version());
                        }
                    }
            );
            case CREATE_VARIANT -> LumaUi.iconButton(
                    "branch",
                    Component.translatable("luma.action.create_idea"),
                    pressed -> this.actions.openBranchDialog(model.version())
            );
        };
        if (actionState.action() == ProjectSaveCardLayout.Action.RESTORE && model.onboardingRestoreTarget()) {
            this.onboardingRestoreButton = button;
        }
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
            boolean createVariantAction,
            boolean tagEditorVisible,
            String tagEditorText,
            List<String> knownTags,
            boolean onboardingRestoreTarget,
            Integer zoneColor
    ) {
        public Model(
                String projectName,
                ProjectVersion version,
                ProjectVariant versionVariant,
                boolean current,
                boolean operationActive,
                int width
        ) {
            this(projectName, version, versionVariant, current, operationActive, width, true, false, "", List.of(), false, null);
        }

        public Model(
                String projectName,
                ProjectVersion version,
                ProjectVariant versionVariant,
                boolean current,
                boolean operationActive,
                int width,
                boolean createVariantAction
        ) {
            this(projectName, version, versionVariant, current, operationActive, width, createVariantAction, false, "", List.of(), false, null);
        }

        public Model(
                String projectName,
                ProjectVersion version,
                ProjectVariant versionVariant,
                boolean current,
                boolean operationActive,
                int width,
                boolean createVariantAction,
                boolean tagEditorVisible,
                String tagEditorText,
                List<String> knownTags,
                boolean onboardingRestoreTarget
        ) {
            this(projectName, version, versionVariant, current, operationActive, width, createVariantAction, tagEditorVisible, tagEditorText, knownTags, onboardingRestoreTarget, null);
        }

        public Model {
            Objects.requireNonNull(projectName, "projectName");
            Objects.requireNonNull(version, "version");
            tagEditorText = TagInputSupport.limit(tagEditorText);
            knownTags = knownTags == null ? List.of() : knownTags;
        }
    }

    public interface Actions {

        void openSaveDetails(String versionId);

        void requestRestore(ProjectVariant variant, ProjectVersion version);

        void openBranchDialog(ProjectVersion version);

        void toggleTagEditor(ProjectVersion version);

        void updateTagEditor(String value);

        void saveTags(ProjectVersion version);

        default void bindTagInput(TextBoxComponent input) {
        }
    }

    interface PreviewFactory {
        UIComponent preview(String projectName, ProjectVersion version, int width, int minHeight, int maxHeight);
    }
}
