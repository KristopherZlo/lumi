package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused one-step branch creation form for the active workspace HEAD. */
public final class LumiBranchScreen extends LumiModalScreen {
    private static final int PANEL_HEIGHT = 126;
    private final Screen parent;
    private final String startingPoint;
    private final BranchNameController controller;
    private EditBox name;
    private LumiButton create;
    private String error = "";
    private LumiModalLayout layout;

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
        beginScreenInit();
        layout = LumiModalLayout.fit(width, height, PANEL_HEIGHT);
        int contentX = layout.x() + 20;
        int contentWidth = layout.width() - 40;
        name = addTextField(contentX, layout.y() + 66, contentWidth,
                Component.translatable("luma.variant.name_input"));
        name.setMaxLength(BranchNameController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.variant.name_input"));
        name.setResponder(value -> create.active = !value.trim().isEmpty());

        int buttonWidth = (contentWidth - 8) / 2;
        create = addButton(contentX, layout.y() + 98, buttonWidth,
                Component.translatable("luma.action.variant_create"),
                this::submit, LumiButton.Kind.PRIMARY);
        create.active = false;
        addButton(contentX + buttonWidth + 8, layout.y() + 98, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
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
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            int headerX = layout.x() + 12;
            int contentRight = layout.x() + layout.width() - 12;
            renderWindow(graphics, layout.x(), layout.y(),
                    layout.width(), layout.height());
            graphics.drawString(font,
                    clippedHeader(title, headerX, contentRight),
                    headerX, layout.y() + 14, LumiTheme.TEXT, false);
            graphics.drawString(font, clippedHeader(
                    Component.translatable(
                            "luma.variants.create_help", startingPoint),
                    headerX, contentRight),
                    headerX, layout.y() + 34, LumiTheme.MUTED, false);
            renderTextField(graphics, name);
            graphics.drawString(font,
                    Component.translatable("luma.variant.name_input"),
                    layout.x() + 20, layout.y() + 55, LumiTheme.TEXT, false);
            if (!error.isEmpty()) {
                graphics.drawString(font, errorText(error),
                        layout.x() + 20, layout.y() + 87,
                        LumiTheme.DANGER, false);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
