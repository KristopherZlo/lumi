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
public final class LumiZonesScreen extends LumiPageScreen {
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
    private LumiButton create;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentWidth;
    private boolean compact;
    private int rowsY;
    private int rowHeight;
    private int rowStride;
    private int scroll;
    private UUID pendingEnterZone;
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
        super(parent, Component.translatable("luma.tab.zones"), ProjectTab.ZONES);
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
        if (pendingEnterZone != null && latest != null) {
            var entered = latest.zones().stream()
                    .filter(zone -> zone.id().equals(pendingEnterZone)
                            && zone.active())
                    .findFirst();
            if (entered.isPresent()) {
                pendingEnterZone = null;
                openDetails.accept(entered.orElseThrow());
                return;
            }
        }
        if (!Objects.equals(snapshot, latest)) {
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginScreenInit();
        snapshot = history.state().snapshot().orElse(null);
        LumiPageLayout shell = pageLayout();
        panelX = shell.contentX();
        panelY = shell.windowY();
        panelWidth = shell.contentWidth();
        panelHeight = shell.windowHeight();
        compact = panelWidth < 300;
        contentX = panelX + 16;
        contentWidth = panelWidth - 32;
        int fieldY = panelY + (compact ? 60 : 72);
        rowsY = panelY + (compact ? COMPACT_ROWS_OFFSET : 126);
        rowHeight = compact ? 42 : 28;
        rowStride = compact ? 46 : 32;
        name = addTextField(contentX, fieldY,
                compact ? contentWidth : Math.max(20, contentWidth - 140),
                Component.translatable("luma.zones.create_title"));
        name.setMaxLength(ZoneScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.zones.create_title"));
        name.setResponder(value -> updateCreateButton());
        Component createLabel = Component.translatable("luma.zones.create_button");
        if (compact) {
            int actionWidth = Math.max(1, (contentWidth - 6) / 2);
            create = addButton(contentX, panelY + 82, actionWidth,
                    createLabel, this::create, LumiButton.Kind.PRIMARY);
            addButton(contentX + actionWidth + 6, panelY + 82, actionWidth,
                    overlayLabel.get(), this::cycleOverlay,
                    LumiButton.Kind.NORMAL);
        } else {
            create = addButton(contentX + contentWidth - 132, panelY + 72, 132,
                    createLabel, this::create, LumiButton.Kind.PRIMARY);
        }
        updateCreateButton();
        if (!compact) {
            addButton(panelX + panelWidth - 140, panelY + 102, 120,
                    overlayLabel.get(), this::cycleOverlay,
                    LumiButton.Kind.NORMAL);
        }
        addZoneRows();
    }

    private void cycleOverlay() {
        cycleOverlay.run();
        rebuildWidgets();
    }

    private void addZoneRows() {
        if (snapshot == null) return;
        int start = Math.min(scroll, snapshot.zones().size());
        int end = Math.min(start + visibleRows(), snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = rowsY + (index - start) * rowStride;
            int right = contentX + contentWidth
                    - LumiScrollbar.GUTTER_WIDTH - 4;
            int actionY = rowY + (compact ? 20 : 4);
            addIconButton(right - 26, actionY,
                    "trash", Component.translatable("luma.zones.delete"),
                    () -> delete.accept(zone), LumiButton.Kind.DANGER);
            addIconButton(right - 58, actionY, "folder",
                    Component.translatable("luma.action.open_details"),
                    () -> openDetails.accept(zone), LumiButton.Kind.NORMAL);
            LumiButton enterButton = addIconButton(right - 90, actionY,
                    zone.active() ? "leave" : "join",
                    Component.translatable(zone.active()
                            ? "luma.zones.leave" : "luma.zones.enter"),
                    () -> select(zone), zone.active()
                            ? LumiButton.Kind.DANGER
                            : LumiButton.Kind.PRIMARY);
            enterButton.active = pendingEnterZone == null;
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
            if (zone.active()) {
                leave.accept(zone.id());
                feedback("luma.status.zone_cleared");
                onClose();
                return;
            }
            enter.accept(zone.id());
            pendingEnterZone = zone.id();
            feedback("luma.status.zone_selected");
            rebuildWidgets();
        } catch (RuntimeException failed) {
            pendingEnterZone = null;
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
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        Component heading = snapshot == null
                ? title : Component.translatable(
                        "luma.screen.zones.title", snapshot.workspaceName());
        renderPageHeader(graphics, panelX, panelY, panelWidth,
                heading, activeZoneText());
        int textWidth = Math.max(1, contentWidth);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.zones.create_help").getString(),
                        textWidth),
                contentX, panelY + (compact ? 47 : 56),
                LumiTheme.MUTED, false);
        renderTextField(graphics, name);
        boolean errorReplacesListTitle = compact && panelHeight < 220
                && !error.isEmpty();
        Component listLabel = errorReplacesListTitle
                ? errorText(error)
                : Component.translatable("luma.zones.list_title");
        graphics.drawString(font,
                font.plainSubstrByWidth(listLabel.getString(), textWidth),
                contentX,
                panelY + (compact ? 104 : 106),
                errorReplacesListTitle
                        ? LumiTheme.DANGER : LumiTheme.TEXT,
                false);
        renderZoneRows(graphics);
        if (snapshot != null) {
            renderScrollbar(
                    graphics, contentX, rowsY, contentWidth,
                    Math.max(0, panelY + panelHeight - rowsY - 8),
                    snapshot.zones().size(), visibleRows(), scroll,
                    value -> scroll = value);
        }
        if (!error.isEmpty() && !errorReplacesListTitle) {
            graphics.drawString(font, font.plainSubstrByWidth(
                            errorText(error).getString(), Math.max(1, panelWidth - 40)),
                    panelX + 20,
                    panelY + panelHeight - 14,
                    LumiTheme.DANGER, false);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
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

    private void renderZoneRows(GuiGraphics graphics) {
        if (snapshot == null || snapshot.zones().isEmpty()) {
            graphics.drawString(font, Component.translatable("luma.zones.empty"),
                    contentX, rowsY + 8, LumiTheme.MUTED, false);
            return;
        }
        int start = Math.min(scroll, snapshot.zones().size());
        int end = Math.min(start + visibleRows(), snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = rowsY + (index - start) * rowStride;
            int rowX = contentX;
            int rowWidth = contentWidth - LumiScrollbar.GUTTER_WIDTH;
            renderPanel(graphics, rowX, rowY,
                    rowWidth, rowHeight);
            graphics.fill(
                    rowX + 7, rowY + 6,
                    rowX + 12, rowY + 11, zone.color());
            int textX = rowX + 18;
            int textWidth = Math.max(0,
                    rowWidth - (compact ? 26 : 120));
            Component metadata = Component.translatable(
                    "luma.zones.cells", zone.cells());
            if (compact) {
                int metadataWidth = Math.min(textWidth, font.width(metadata));
                int nameWidth = Math.max(0, textWidth - metadataWidth - 4);
                graphics.drawString(font,
                        font.plainSubstrByWidth(zone.name(), nameWidth),
                        textX, rowY + 5, LumiTheme.TEXT, false);
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                metadata.getString(), metadataWidth),
                        textX + textWidth - metadataWidth, rowY + 5,
                        LumiTheme.MUTED, false);
            } else {
                graphics.drawString(font,
                        font.plainSubstrByWidth(zone.name(), textWidth),
                        textX, rowY + 5, LumiTheme.TEXT, false);
                graphics.drawString(font,
                        font.plainSubstrByWidth(metadata.getString(), textWidth),
                        textX, rowY + 17, LumiTheme.MUTED, false);
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
