package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Zone-scoped Save form and bounded hidden history view. */
public final class LumiZoneDetailsScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;
    private static final int PAGE_SIZE = 4;
    private final Screen parent;
    private final HistorySnapshotPayload.ZoneView zone;
    private final ZoneDetailsController controller;
    private final Consumer<HistorySnapshotPayload.Version> openRestore;
    private EditBox message;
    private Button save;
    private int panelX;
    private int panelY;
    private int page;
    private String error = "";

    public LumiZoneDetailsScreen(
            Screen parent,
            HistorySnapshotPayload.ZoneView zone,
            ZoneDetailsController controller,
            Consumer<HistorySnapshotPayload.Version> openRestore) {
        super(Component.translatable("luma.zones.details_title", zone.name()));
        this.parent = parent;
        this.zone = Objects.requireNonNull(zone, "zone");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, panelX + 20, panelY + 72, contentWidth - 140, 20,
                Component.translatable("luma.zones.save_input"));
        message.setMaxLength(ZoneDetailsController.MAX_MESSAGE_LENGTH);
        message.setHint(Component.translatable("luma.zones.save_input"));
        message.setResponder(value ->
                save.active = zone.active() && !value.trim().isEmpty());
        addRenderableWidget(message);
        save = addRenderableWidget(Button.builder(
                Component.translatable("luma.zones.save_button"), ignored -> save())
                .bounds(panelX + panelWidth - 152, panelY + 72, 132, 20).build());
        save.active = false;
        addHistoryButtons(panelWidth);
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(panelX + 20, panelY + 298, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(panelX + 52, panelY + 298, 28, 20).build())
                .active = (page + 1) * PAGE_SIZE < zone.versions().size();
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 140, panelY + 298, 120, 20).build());
    }

    private void addHistoryButtons(int panelWidth) {
        int start = Math.min(page * PAGE_SIZE, zone.versions().size());
        int end = Math.min(start + PAGE_SIZE, zone.versions().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = zone.versions().get(index);
            int rowY = panelY + 132 + (index - start) * 38;
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.restore"),
                    ignored -> openRestore.accept(version))
                    .bounds(panelX + panelWidth - 108, rowY + 7, 88, 20).build());
        }
    }

    private void save() {
        ZoneDetailsController.Submission submission =
                controller.save(zone.id(), message.getValue(), zone.active());
        error = submission.error();
        if (submission.accepted()) {
            feedback("luma.status.save_started");
            minecraft.setScreen(parent);
        }
    }

    private void changePage(int delta) {
        page = Math.max(0, page + delta);
        rebuildWidgets();
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
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
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, zone.color());
        graphics.drawCenteredString(font,
                Component.translatable(zone.active()
                        ? "luma.zones.details_active" : "luma.zones.details_inactive"),
                width / 2, panelY + 36, 0xffaeb6c2);
        graphics.drawString(font, Component.translatable("luma.zones.save_help"),
                panelX + 20, panelY + 56, 0xffaeb6c2, false);
        graphics.drawString(font, Component.translatable("luma.zones.history_title"),
                panelX + 20, panelY + 108, 0xfff0f3f6, false);
        renderHistory(graphics, panelWidth);
        if (!error.isEmpty()) {
            Component text = error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
            graphics.drawString(font, text, panelX + 88, panelY + 304,
                    0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHistory(GuiGraphics graphics, int panelWidth) {
        if (zone.versions().isEmpty()) {
            graphics.drawString(font, Component.translatable("luma.zones.history_empty"),
                    panelX + 20, panelY + 140, 0xff8f9aa8, false);
            return;
        }
        int start = Math.min(page * PAGE_SIZE, zone.versions().size());
        int end = Math.min(start + PAGE_SIZE, zone.versions().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = zone.versions().get(index);
            int rowY = panelY + 132 + (index - start) * 38;
            graphics.fill(panelX + 20, rowY,
                    panelX + panelWidth - 20, rowY + 34, 0xff20252c);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 190),
                    panelX + 28, rowY + 7, 0xfff0f3f6, false);
            graphics.drawString(font, version.author(),
                    panelX + 28, rowY + 20, 0xff8f9aa8, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
