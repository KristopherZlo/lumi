package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Focused one-step Save form retained for the Alt+S workflow. */
public final class LumiSaveScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 182;
    private final Screen parent;
    private final ClientHistoryStore history;
    private final SaveScreenController controller;
    private final Runnable refresh;
    private EditBox message;
    private Button save;
    private Button amend;
    private String error = "";
    private int panelX;
    private int panelY;

    public LumiSaveScreen(
            Screen parent,
            ClientHistoryStore history,
            SaveScreenController controller,
            Runnable refresh) {
        super(Component.translatable("luma.save.summary_title"));
        this.parent = parent;
        this.history = java.util.Objects.requireNonNull(history, "history");
        this.controller = java.util.Objects.requireNonNull(controller, "controller");
        this.refresh = java.util.Objects.requireNonNull(refresh, "refresh");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, contentX, panelY + 93, contentWidth, 20,
                Component.translatable("luma.save.name_input"));
        message.setMaxLength(SaveScreenController.MAX_NAME_LENGTH);
        message.setHint(Component.translatable("luma.save.name_input"));
        message.setResponder(value -> {
            boolean active = !value.trim().isEmpty();
            save.active = active;
            amend.active = active;
        });
        addRenderableWidget(message);
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.refresh_preview"),
                ignored -> refreshPreview())
                .bounds(panelX + panelWidth - 140, panelY + 54, 120, 20).build());

        int buttonWidth = (contentWidth - 16) / 3;
        save = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.save_build"),
                ignored -> submit(SaveScreenController.Intent.SAVE))
                .bounds(contentX, panelY + 140, buttonWidth, 20).build());
        save.active = false;
        amend = addRenderableWidget(Button.builder(
                Component.translatable("luma.action.amend_version"),
                ignored -> submit(SaveScreenController.Intent.AMEND))
                .bounds(contentX + buttonWidth + 8, panelY + 140, buttonWidth, 20).build());
        amend.active = false;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.cancel"), ignored -> onClose())
                .bounds(contentX + (buttonWidth + 8) * 2,
                        panelY + 140, buttonWidth, 20).build());
        refreshPreview();
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
            submit(SaveScreenController.Intent.SAVE);
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit(SaveScreenController.Intent intent) {
        SaveScreenController.Submission submission =
                controller.submit(message.getValue(), intent);
        error = submission.error();
        if (submission.accepted()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("luma.status.save_started"), true);
            }
            minecraft.setScreen(parent);
        }
    }

    private void refreshPreview() {
        try {
            refresh.run();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi preview could not refresh" : failed.getMessage();
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
        int pending = history.state().snapshot()
                .map(snapshot -> snapshot.pendingKeys()).orElse(0);
        graphics.drawString(font,
                Component.translatable("luma.dashboard.workspace_pending", pending),
                panelX + 20, panelY + 60,
                pending == 0 ? 0xffaeb6c2 : 0xffffd166, false);
        graphics.drawString(font, Component.translatable("luma.save.name_input"),
                panelX + 20, panelY + 80, 0xffdbe2ea, false);
        if (!error.isEmpty()) {
            Component text = error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
            graphics.drawString(font, text, panelX + 20, panelY + 118,
                    0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
