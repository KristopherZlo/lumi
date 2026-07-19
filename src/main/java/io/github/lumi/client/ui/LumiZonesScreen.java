package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Thin workspace-zone screen backed by immutable server snapshots. */
public final class LumiZonesScreen extends LumiLegacyPageScreen {
    private static final int MAX_ROWS = 8;
    private final ClientHistoryStore history;
    private final ZoneScreenController controller;
    private final Consumer<HistorySnapshotPayload.ZoneView> openDetails;
    private final Consumer<HistorySnapshotPayload.ZoneView> delete;
    private final Supplier<Component> overlayLabel;
    private final Runnable cycleOverlay;
    private final Consumer<UUID> enter;
    private final Consumer<UUID> leave;
    private HistorySnapshotPayload snapshot;
    private EditBox name;
    private LumiLegacyButton create;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;
    private String error = "";

    public LumiZonesScreen(
            Screen parent,
            ClientHistoryStore history,
            ZoneScreenController controller,
            Consumer<HistorySnapshotPayload.ZoneView> openDetails,
            Consumer<HistorySnapshotPayload.ZoneView> delete,
            Supplier<Component> overlayLabel,
            Runnable cycleOverlay,
            Consumer<UUID> enter,
            Consumer<UUID> leave) {
        super(parent, Component.translatable("luma.tab.zones"), LegacyProjectTab.ZONES);
        this.history = Objects.requireNonNull(history, "history");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.openDetails = Objects.requireNonNull(openDetails, "openDetails");
        this.delete = Objects.requireNonNull(delete, "delete");
        this.overlayLabel = Objects.requireNonNull(
                overlayLabel, "overlayLabel");
        this.cycleOverlay = Objects.requireNonNull(
                cycleOverlay, "cycleOverlay");
        this.enter = Objects.requireNonNull(enter, "enter");
        this.leave = Objects.requireNonNull(leave, "leave");
    }

    @Override
    public void tick() {
        super.tick();
        HistorySnapshotPayload latest =
                history.state().snapshot().orElse(null);
        if (!Objects.equals(snapshot, latest)) {
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        snapshot = history.state().snapshot().orElse(null);
        LegacyWorkspaceLayout shell = pageLayout();
        panelX = shell.contentX();
        panelY = shell.windowY();
        panelWidth = shell.contentWidth();
        panelHeight = shell.windowHeight();
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(font, contentX, panelY + 73, contentWidth - 140, 16,
                Component.translatable("luma.zones.create_title"));
        name.setMaxLength(ZoneScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.zones.create_title"));
        name.setBordered(false);
        name.setTextColor(LegacyLumiTheme.TEXT);
        name.setResponder(value -> updateCreateButton());
        addRenderableWidget(name);
        create = addLegacyButton(contentX + contentWidth - 132, panelY + 72, 132,
                Component.translatable("luma.zones.create_button"),
                this::create, LumiLegacyButton.Kind.PRIMARY);
        updateCreateButton();
        addLegacyButton(
                panelX + panelWidth - 140, panelY + 102, 120,
                overlayLabel.get(), this::cycleOverlay,
                LumiLegacyButton.Kind.NORMAL);
        addZoneRows(panelWidth);
    }

    private void cycleOverlay() {
        cycleOverlay.run();
        rebuildWidgets();
    }

    private void addZoneRows(int panelWidth) {
        if (snapshot == null) return;
        int start = Math.min(scroll, snapshot.zones().size());
        int end = Math.min(start + visibleRows(), snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = panelY + 126 + (index - start) * 32;
            int right = panelX + panelWidth - 24;
            addLegacyIconButton(right - 26, rowY + 4,
                    "trash", Component.translatable("luma.zones.delete"),
                    () -> delete.accept(zone), LumiLegacyButton.Kind.DANGER);
            addLegacyIconButton(right - 58, rowY + 4, "folder",
                    Component.translatable("luma.action.open_details"),
                    () -> openDetails.accept(zone), LumiLegacyButton.Kind.NORMAL);
            addLegacyIconButton(right - 90, rowY + 4,
                    zone.active() ? "leave" : "join",
                    Component.translatable(zone.active()
                            ? "luma.zones.leave" : "luma.zones.enter"),
                    () -> select(zone), zone.active()
                            ? LumiLegacyButton.Kind.DANGER
                            : LumiLegacyButton.Kind.PRIMARY);
        }
    }

    private void updateCreateButton() {
        if (create != null && name != null) {
            create.active = !name.getValue().trim().isEmpty();
        }
    }

    private void create() {
        ZoneScreenController.Submission submission =
                controller.create(name.getValue());
        error = submission.error();
        if (submission.accepted()) {
            feedback("luma.status.zone_created");
            onClose();
        }
    }

    private void select(HistorySnapshotPayload.ZoneView zone) {
        try {
            (zone.active() ? leave : enter).accept(zone.id());
            feedback(zone.active()
                    ? "luma.status.zone_cleared" : "luma.status.zone_selected");
            onClose();
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi zone could not be updated" : failed.getMessage();
        }
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
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
            create();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        Component heading = snapshot == null
                ? title : Component.translatable(
                        "luma.screen.zones.title", snapshot.workspaceName());
        graphics.drawCenteredString(font, heading, panelX + panelWidth / 2, panelY + 16,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font, activeZoneText(),
                panelX + panelWidth / 2, panelY + 36, LegacyLumiTheme.MUTED);
        graphics.drawString(font, Component.translatable("luma.zones.create_help"),
                panelX + 20, panelY + 56, LegacyLumiTheme.MUTED, false);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 69,
                panelWidth - 178, 20,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        graphics.drawString(font, Component.translatable("luma.zones.list_title"),
                panelX + 20, panelY + 106, LegacyLumiTheme.TEXT, false);
        renderZoneRows(graphics, panelWidth);
        if (!error.isEmpty()) {
            graphics.drawString(font, errorText(error), panelX + 88, panelY + 304,
                    LegacyLumiTheme.DANGER, false);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private Component activeZoneText() {
        if (snapshot == null) {
            return Component.translatable("luma.status.zones_loading");
        }
        return snapshot.zones().stream().filter(HistorySnapshotPayload.ZoneView::active)
                .findFirst()
                .<Component>map(zone -> Component.translatable(
                        "luma.zones.current_zone", zone.name()))
                .orElseGet(() -> Component.translatable("luma.zones.current_none"));
    }

    private void renderZoneRows(GuiGraphics graphics, int panelWidth) {
        if (snapshot == null || snapshot.zones().isEmpty()) {
            graphics.drawString(font, Component.translatable("luma.zones.empty"),
                    panelX + 20, panelY + 134, LegacyLumiTheme.MUTED, false);
            return;
        }
        int start = Math.min(scroll, snapshot.zones().size());
        int end = Math.min(start + visibleRows(), snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = panelY + 126 + (index - start) * 32;
            renderLegacyPanel(graphics, panelX + 20, rowY,
                    panelWidth - 40, 28);
            graphics.fill(
                    panelX + 27, rowY + 6, panelX + 32, rowY + 11, zone.color());
            graphics.drawString(font,
                    font.plainSubstrByWidth(zone.name(), panelWidth - 150),
                    panelX + 38, rowY + 5, LegacyLumiTheme.TEXT, false);
            Component metadata = Component.literal(zone.cells() + " cells");
            graphics.drawString(font,
                    font.plainSubstrByWidth(metadata.getString(), panelWidth - 150),
                    panelX + 38, rowY + 17, LegacyLumiTheme.MUTED, false);
        }
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, Math.max(1, (panelHeight - 126) / 32));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (snapshot != null && x >= panelX && x < panelX + panelWidth
                && y >= panelY + 126 && y < panelY + panelHeight) {
            int maximum = Math.max(0, snapshot.zones().size() - visibleRows());
            int replacement = Math.max(0, Math.min(
                    maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) {
                scroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public boolean isPauseScreen() { return false; }
}
