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
    private static final int COMPACT_ROWS_OFFSET = 118;
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
    private boolean compact;
    private int rowsY;
    private int rowHeight;
    private int rowStride;
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
        compact = panelWidth < 300;
        int contentX = panelX + (compact ? 16 : 20);
        int contentWidth = panelWidth - (compact ? 32 : 40);
        int fieldY = panelY + (compact ? 60 : 73);
        rowsY = panelY + (compact ? COMPACT_ROWS_OFFSET : 126);
        rowHeight = compact ? 42 : 28;
        rowStride = compact ? 46 : 32;
        name = new EditBox(font, contentX, fieldY,
                compact ? contentWidth : Math.max(20, contentWidth - 140),
                INPUT_HEIGHT,
                Component.translatable("luma.zones.create_title"));
        name.setMaxLength(ZoneScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.zones.create_title"));
        name.setBordered(false);
        name.setTextColor(LegacyLumiTheme.TEXT);
        name.setResponder(value -> updateCreateButton());
        addRenderableWidget(name);
        Component createLabel = Component.translatable("luma.zones.create_button");
        if (compact) {
            int actionWidth = Math.max(1, (contentWidth - 6) / 2);
            create = addLegacyButton(contentX, panelY + 82, actionWidth,
                    createLabel, this::create, LumiLegacyButton.Kind.PRIMARY);
            addLegacyButton(contentX + actionWidth + 6, panelY + 82, actionWidth,
                    overlayLabel.get(), this::cycleOverlay,
                    LumiLegacyButton.Kind.NORMAL);
        } else {
            create = addLegacyButton(contentX + contentWidth - 132, panelY + 72, 132,
                    createLabel, this::create, LumiLegacyButton.Kind.PRIMARY);
        }
        updateCreateButton();
        if (!compact) {
            addLegacyButton(panelX + panelWidth - 140, panelY + 102, 120,
                    overlayLabel.get(), this::cycleOverlay,
                    LumiLegacyButton.Kind.NORMAL);
        }
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
            int rowY = rowsY + (index - start) * rowStride;
            int right = panelX + panelWidth - 24;
            int actionY = rowY + (compact ? 20 : 4);
            addLegacyIconButton(right - 26, actionY,
                    "trash", Component.translatable("luma.zones.delete"),
                    () -> delete.accept(zone), LumiLegacyButton.Kind.DANGER);
            addLegacyIconButton(right - 58, actionY, "folder",
                    Component.translatable("luma.action.open_details"),
                    () -> openDetails.accept(zone), LumiLegacyButton.Kind.NORMAL);
            addLegacyIconButton(right - 90, actionY,
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
        graphics.drawCenteredString(font, clippedCenteredHeader(
                        heading, panelX + panelWidth / 2,
                        panelX + 16, panelX + panelWidth - 16),
                panelX + panelWidth / 2, panelY + 16,
                LegacyLumiTheme.TEXT);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(
                        activeZoneText().getString(), Math.max(1, panelWidth - 32)),
                panelX + panelWidth / 2, panelY + 36, LegacyLumiTheme.MUTED);
        int textX = panelX + (compact ? 16 : 20);
        int textWidth = Math.max(1, panelWidth - (compact ? 32 : 40));
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.zones.create_help").getString(),
                        textWidth),
                textX, panelY + (compact ? 47 : 56),
                LegacyLumiTheme.MUTED, false);
        LegacyLumiTheme.outlined(graphics,
                name.getX() - 2, name.getY() - 2,
                name.getWidth() + 4, name.getHeight() + 4,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        boolean errorReplacesListTitle = compact && panelHeight < 220
                && !error.isEmpty();
        Component listLabel = errorReplacesListTitle
                ? errorText(error)
                : Component.translatable("luma.zones.list_title");
        graphics.drawString(font,
                font.plainSubstrByWidth(listLabel.getString(), textWidth),
                panelX + (compact ? 16 : 20),
                panelY + (compact ? 104 : 106),
                errorReplacesListTitle
                        ? LegacyLumiTheme.DANGER : LegacyLumiTheme.TEXT,
                false);
        renderZoneRows(graphics, panelWidth);
        if (!error.isEmpty() && !errorReplacesListTitle) {
            graphics.drawString(font, font.plainSubstrByWidth(
                            errorText(error).getString(), Math.max(1, panelWidth - 40)),
                    panelX + 20,
                    panelY + panelHeight - 14,
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
                    panelX + 20, rowsY + 8, LegacyLumiTheme.MUTED, false);
            return;
        }
        int start = Math.min(scroll, snapshot.zones().size());
        int end = Math.min(start + visibleRows(), snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = rowsY + (index - start) * rowStride;
            int rowX = panelX + (compact ? 16 : 20);
            renderLegacyPanel(graphics, rowX, rowY,
                    panelWidth - (compact ? 32 : 40), rowHeight);
            graphics.fill(
                    panelX + (compact ? 23 : 27), rowY + 6,
                    panelX + (compact ? 28 : 32), rowY + 11, zone.color());
            int textX = panelX + (compact ? 34 : 38);
            int textWidth = Math.max(0, panelWidth - (compact ? 58 : 160));
            Component metadata = Component.translatable(
                    "luma.zones.cells", zone.cells());
            if (compact) {
                int metadataWidth = Math.min(textWidth, font.width(metadata));
                int nameWidth = Math.max(0, textWidth - metadataWidth - 4);
                graphics.drawString(font,
                        font.plainSubstrByWidth(zone.name(), nameWidth),
                        textX, rowY + 5, LegacyLumiTheme.TEXT, false);
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                metadata.getString(), metadataWidth),
                        textX + textWidth - metadataWidth, rowY + 5,
                        LegacyLumiTheme.MUTED, false);
            } else {
                graphics.drawString(font,
                        font.plainSubstrByWidth(zone.name(), textWidth),
                        textX, rowY + 5, LegacyLumiTheme.TEXT, false);
                graphics.drawString(font,
                        font.plainSubstrByWidth(metadata.getString(), textWidth),
                        textX, rowY + 17, LegacyLumiTheme.MUTED, false);
            }
        }
    }

    private int visibleRows() {
        return visibleRows(panelHeight, compact);
    }

    static int visibleRows(int panelHeight, boolean compact) {
        int rowOffset = compact ? COMPACT_ROWS_OFFSET : 126;
        int height = compact ? 42 : 28;
        int stride = compact ? 46 : 32;
        int available = panelHeight - rowOffset;
        if (available < height) return 0;
        return Math.min(MAX_ROWS,
                1 + (available - height) / stride);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (snapshot != null && x >= panelX && x < panelX + panelWidth
                && y >= rowsY && y < panelY + panelHeight) {
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
