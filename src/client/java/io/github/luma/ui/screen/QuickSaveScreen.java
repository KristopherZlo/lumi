package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVersionTags;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.TagInputSupport;
import io.github.luma.ui.TagSuggestionComponent;
import io.github.luma.ui.controller.QuickSaveScreenController;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class QuickSaveScreen extends LumaScreen {

    private static final int MIN_DIALOG_WIDTH = 220;
    private static final int MAX_DIALOG_WIDTH = 320;

    private final Minecraft client = Minecraft.getInstance();
    private final QuickSaveScreenController controller = new QuickSaveScreenController();
    private String saveMessage = "";
    private String saveTags = "";
    private String status = "luma.status.quick_save_ready";
    private TextBoxComponent saveNameInput;
    private TextBoxComponent saveTagsInput;

    public QuickSaveScreen() {
        super(Component.translatable("luma.screen.quick_save.title"));
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        root.verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout frame = LumaUi.modalFrame(this.dialogWidth());
        root.child(frame);

        frame.child(LumaUi.closeHeader(Component.translatable("luma.screen.save.title"), button -> this.onClose()));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));
        frame.child(this.messageField());
        frame.child(this.tagsField());
        frame.child(this.actions());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            this.save();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && this.acceptTagCompletion()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.closeLumaUi();
    }

    private FlowLayout messageField() {
        this.saveNameInput = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        this.saveNameInput.setHint(Component.translatable("luma.save.name_input"));
        this.saveNameInput.onChanged().subscribe(value -> {
            this.saveMessage = value == null ? "" : value;
        });
        return LumaUi.formField(
                Component.translatable("luma.save.name_input"),
                Component.translatable("luma.quick_save.name_help"),
                this.saveNameInput
        );
    }

    private FlowLayout tagsField() {
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.saveTags);
        this.saveTagsInput = input;
        input.setHint(Component.translatable("luma.history.tags_input"));
        List<String> knownTags = TagInputSupport.knownTags(this.controller.currentWorkspaceVersions());
        TagInputSupport.configure(input, this.saveTags, knownTags, true);

        FlowLayout inputColumn = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        inputColumn.gap(2);
        TagSuggestionComponent suggestions = new TagSuggestionComponent(
                () -> this.saveTags,
                () -> knownTags,
                true,
                accepted -> {
                    this.saveTags = accepted;
                    input.setValue(accepted);
                    input.setCursorPosition(accepted.length());
                }
        );
        input.onChanged().subscribe(value -> {
            this.saveTags = TagInputSupport.limit(value);
            suggestions.refresh();
        });
        inputColumn.child(input);
        inputColumn.child(suggestions);
        return LumaUi.formField(
                Component.translatable("luma.save.tags_title"),
                null,
                inputColumn
        );
    }

    private FlowLayout actions() {
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.primaryButton(Component.translatable("luma.action.save"), button -> this.save()));
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.onClose()));
        return actions;
    }

    private void save() {
        String result = this.controller.saveCurrentWorkspace(this.saveMessage, ProjectVersionTags.parse(this.saveTags));
        if ("luma.status.save_started".equals(result)) {
            this.client.gui.setOverlayMessage(ActionBarMessagePresenter.info(result), false);
            this.closeLumaUi();
            return;
        }
        this.refresh(result);
    }

    private boolean acceptTagCompletion() {
        List<String> knownTags = TagInputSupport.knownTags(this.controller.currentWorkspaceVersions());
        if (!TagInputSupport.hasSuggestion(this.saveTags, knownTags)) {
            return false;
        }
        this.saveTags = this.saveTagsInput == null
                ? TagInputSupport.acceptSuggestion(this.saveTags, knownTags, true)
                : TagInputSupport.acceptInto(this.saveTagsInput, this.saveTags, knownTags, true);
        return true;
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.quick_save_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private int dialogWidth() {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, this.width - 20));
    }
}
