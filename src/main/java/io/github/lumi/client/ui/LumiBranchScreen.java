package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused one-step branch creation form for the active workspace HEAD. */
public final class LumiBranchScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 158;
    private final Screen parent;
    private final String startingPoint;
    private final BranchNameController controller;
    private EditBox name;
    private Button create;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiBranchScreen(
            Screen parent,
            String startingPoint,
            BranchNameController controller) {
        super(Component.translatable("luma.variants.create_title"));
        this.parent = parent;
        this.startingPoint = startingPoint;
        this.controller = controller;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(
                font, contentX, panelY + 69, contentWidth, 20,
                Component.translatable("luma.variant.name_input"));
        name.setMaxLength(BranchNameController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.variant.name_input"));
        name.setResponder(value -> create.active = !value.trim().isEmpty());
        addRenderableWidget(name);

        int buttonWidth = (contentWidth - 8) / 2;
        create = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.variant_create"), ignored -> submit())
                .bounds(contentX, panelY + 116, buttonWidth, 20).build());
        create.active = false;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(contentX + buttonWidth + 8, panelY + 116, buttonWidth, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        if (name != null) {
            setInitialFocus(name);
            name.setFocused(true);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) && create.active) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        BranchNameController.Submission submission = controller.submit(name.getValue());
        error = submission.error();
        if (submission.accepted()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.variant_created"), true);
            }
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 17, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.translatable("luma.variants.create_help", startingPoint),
                width / 2, panelY + 36, 0xffaeb6c2);
        graphics.drawString(font, Component.translatable("luma.variant.name_input"),
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
