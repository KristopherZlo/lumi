package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Zone-scoped Save form and bounded hidden history view. */
public final class LumiZoneDetailsScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;
    private static final int PAGE_SIZE = 4;
    private final Screen parent;
    private final HistorySnapshotPayload.ZoneView zone;
    private final ZoneDetailsController controller;
    private final Consumer<HistorySnapshotPayload.Version> openRestore;
    private final Consumer<VersionCompareController.Target> openCompare;
    private final VersionCompareController compareController = new VersionCompareController();
    private EditBox message;
    private LumiLegacyButton save;
    private int panelX;
    private int panelY;
    private int page;
    private String error = "";

    public LumiZoneDetailsScreen(
            Screen parent,
            HistorySnapshotPayload.ZoneView zone,
            ZoneDetailsController controller,
            Consumer<HistorySnapshotPayload.Version> openRestore,
            Consumer<VersionCompareController.Target> openCompare) {
        super(Component.translatable("luma.zones.details_title", zone.name()));
        this.parent = parent;
        this.zone = Objects.requireNonNull(zone, "zone");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
        this.openCompare = Objects.requireNonNull(openCompare, "openCompare");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, panelX + 20, panelY + 72, contentWidth - 140, 20,
                Component.translatable("luma.zones.save_input"));
        message.setMaxLength(ZoneDetailsController.MAX_MESSAGE_LENGTH);
        message.setHint(Component.translatable("luma.zones.save_input"));
        message.setBordered(false);
        message.setTextColor(LegacyLumiTheme.TEXT);
        message.setResponder(value ->
                save.active = zone.active() && !value.trim().isEmpty());
        addRenderableWidget(message);
        save = addLegacyButton(panelX + panelWidth - 152, panelY + 72, 132,
                Component.translatable("luma.zones.save_button"),
                this::save, LumiLegacyButton.Kind.PRIMARY);
        save.active = false;
        addHistoryButtons(panelWidth);
        LumiLegacyButton previous = addLegacyButton(
                panelX + 20, panelY + 298, 28, Component.literal("<"),
                () -> changePage(-1), LumiLegacyButton.Kind.NORMAL);
        previous.active = page > 0;
        LumiLegacyButton next = addLegacyButton(
                panelX + 52, panelY + 298, 28, Component.literal(">"),
                () -> changePage(1), LumiLegacyButton.Kind.NORMAL);
        next.active = (page + 1) * PAGE_SIZE < zone.versions().size();
        addLegacyButton(panelX + panelWidth - 140, panelY + 298, 120,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addHistoryButtons(int panelWidth) {
        int start = Math.min(page * PAGE_SIZE, zone.versions().size());
        int end = Math.min(start + PAGE_SIZE, zone.versions().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = zone.versions().get(index);
            int rowY = panelY + 132 + (index - start) * 38;
            compareController.target(zone.versions(), index).ifPresent(target ->
                    addLegacyButton(panelX + panelWidth - 176, rowY + 7, 60,
                            Component.translatable("luma.action.compare"),
                            () -> openCompare.accept(target),
                            LumiLegacyButton.Kind.NORMAL));
            addLegacyButton(panelX + panelWidth - 108, rowY + 7, 88,
                    Component.translatable("luma.action.restore"),
                    () -> openRestore.accept(version),
                    LumiLegacyButton.Kind.PRIMARY);
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
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, zone.color());
        graphics.drawCenteredString(font,
                Component.translatable(zone.active()
                        ? "luma.zones.details_active" : "luma.zones.details_inactive"),
                width / 2, panelY + 36, LegacyLumiTheme.MUTED);
        graphics.drawString(font, Component.translatable("luma.zones.save_help"),
                panelX + 20, panelY + 56, LegacyLumiTheme.MUTED, false);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 70,
                panelWidth - 168, 24,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        graphics.drawString(font, Component.translatable("luma.zones.history_title"),
                panelX + 20, panelY + 108, LegacyLumiTheme.TEXT, false);
        renderHistory(graphics, panelWidth);
        if (!error.isEmpty()) {
            graphics.drawString(font, errorText(error),
                    panelX + 88, panelY + 304, LegacyLumiTheme.DANGER, false);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderHistory(GuiGraphics graphics, int panelWidth) {
        if (zone.versions().isEmpty()) {
            graphics.drawString(font, Component.translatable("luma.zones.history_empty"),
                    panelX + 20, panelY + 140, LegacyLumiTheme.MUTED, false);
            return;
        }
        int start = Math.min(page * PAGE_SIZE, zone.versions().size());
        int end = Math.min(start + PAGE_SIZE, zone.versions().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.Version version = zone.versions().get(index);
            int rowY = panelY + 132 + (index - start) * 38;
            renderLegacyPanel(graphics,
                    panelX + 20, rowY, panelWidth - 40, 34);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 190),
                    panelX + 28, rowY + 7, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, version.author(),
                    panelX + 28, rowY + 20, LegacyLumiTheme.MUTED, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
