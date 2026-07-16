package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused one-step Save form retained for the Alt+S workflow. */
public final class LumiSaveScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 158;
    private final Screen parent;
    private final SaveScreenController controller;
    private EditBox message;
    private Button save;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiSaveScreen(Screen parent, SaveScreenController controller) {
        super(Component.translatable("luma.save.summary_title"));
        this.parent = parent;
        this.controller = controller;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, contentX, panelY + 69, contentWidth, 20,
                Component.translatable("luma.save.name_input"));
        message.setMaxLength(SaveScreenController.MAX_NAME_LENGTH);
        message.setHint(Component.translatable("luma.save.name_input"));
        message.setResponder(value -> save.active = !value.trim().isEmpty());
        addRenderableWidget(message);

        int buttonWidth = (contentWidth - 8) / 2;
        save = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.save_build"), ignored -> submit())
                .bounds(contentX, panelY + 116, buttonWidth, 20).build());
        save.active = false;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(contentX + buttonWidth + 8, panelY + 116, buttonWidth, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        if (message != null) {
            setInitialFocus(message);
            message.setFocused(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) && save.active) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        SaveScreenController.Submission submission = controller.submit(message.getValue());
        error = submission.error();
        if (submission.accepted()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.save_started"), true);
            }
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + Math.min(PANEL_WIDTH, width - 32),
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 17, 0xffffffff);
        graphics.drawCenteredString(font, Component.translatable("luma.save.summary_help"),
                width / 2, panelY + 36, 0xffaeb6c2);
        graphics.drawString(font, Component.translatable("luma.save.name_input"),
                panelX + 20, panelY + 56, 0xffdbe2ea, false);
        if (!error.isEmpty()) {
            Component text = error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
            graphics.drawString(font, text, panelX + 20, panelY + 94, 0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
