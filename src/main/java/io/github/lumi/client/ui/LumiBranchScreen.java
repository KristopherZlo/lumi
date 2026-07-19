package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused one-step branch creation form for the active workspace HEAD. */
public final class LumiBranchScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 158;
    private final Screen parent;
    private final String startingPoint;
    private final BranchNameController controller;
    private EditBox name;
    private LumiLegacyButton create;
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
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(
                font, contentX, panelY + 70, contentWidth, 16,
                Component.translatable("luma.variant.name_input"));
        name.setMaxLength(BranchNameController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.variant.name_input"));
        name.setBordered(false);
        name.setTextColor(LegacyLumiTheme.TEXT);
        name.setResponder(value -> create.active = !value.trim().isEmpty());
        addRenderableWidget(name);

        int buttonWidth = (contentWidth - 8) / 2;
        create = addLegacyButton(contentX, panelY + 116, buttonWidth,
                Component.translatable("luma.action.variant_create"),
                this::submit, LumiLegacyButton.Kind.PRIMARY);
        create.active = false;
        addLegacyButton(contentX + buttonWidth + 8, panelY + 116, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
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
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            int headerX = panelX + 12;
            int contentRight = panelX + panelWidth - 12;
            renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawString(font,
                    clippedHeader(title, headerX, contentRight),
                    headerX, panelY + 14, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, clippedHeader(
                    Component.translatable(
                            "luma.variants.create_help", startingPoint),
                    headerX, contentRight),
                    headerX, panelY + 34, LegacyLumiTheme.MUTED, false);
            LegacyLumiTheme.outlined(graphics, panelX + 14, panelY + 66,
                    panelWidth - 28, 20,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            graphics.drawString(font,
                    Component.translatable("luma.variant.name_input"),
                    panelX + 20, panelY + 55, LegacyLumiTheme.TEXT, false);
            if (!error.isEmpty()) {
                graphics.drawString(font, errorText(error),
                        panelX + 20, panelY + 96,
                        LegacyLumiTheme.DANGER, false);
            }
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
