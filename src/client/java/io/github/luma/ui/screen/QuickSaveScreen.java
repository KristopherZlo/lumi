package io.github.luma.ui.screen;

import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.QuickSaveScreenController;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.VerticalAlignment;
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
    private String status = "luma.status.quick_save_ready";
    private TextBoxComponent saveNameInput;
    private ButtonComponent saveButton;

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

        frame.child(LumaUi.value(Component.translatable("luma.screen.quick_save.title")));
        frame.child(LumaUi.statusBanner(Component.translatable(this.status)));
        frame.child(this.messageField());
        frame.child(this.actions());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.canSave()) {
                this.save();
            }
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
            this.updateSaveButtonActive();
        });
        return LumaUi.formField(
                Component.translatable("luma.save.name_input"),
                Component.translatable("luma.quick_save.name_help"),
                this.saveNameInput
        );
    }

    private FlowLayout actions() {
        FlowLayout actions = LumaUi.actionRow();
        this.saveButton = LumaUi.primaryButton(Component.translatable("luma.action.save"), button -> this.save());
        this.updateSaveButtonActive();
        actions.child(this.saveButton);
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> this.onClose()));
        return actions;
    }

    private void save() {
        String result = this.controller.saveCurrentWorkspace(this.saveMessage);
        if ("luma.status.save_started".equals(result)) {
            this.client.gui.setOverlayMessage(ActionBarMessagePresenter.info(result), false);
            this.closeLumaUi();
            return;
        }
        this.refresh(result);
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

    private void updateSaveButtonActive() {
        if (this.saveButton != null) {
            this.saveButton.active(this.canSave());
        }
    }

    private boolean canSave() {
        return !this.saveMessage.trim().isBlank();
    }

    private int dialogWidth() {
        return Math.max(MIN_DIALOG_WIDTH, Math.min(MAX_DIALOG_WIDTH, this.width - 20));
    }
}
