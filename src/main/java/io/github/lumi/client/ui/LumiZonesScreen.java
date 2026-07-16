package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Thin workspace-zone screen backed by immutable server snapshots. */
public final class LumiZonesScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;
    private static final int PAGE_SIZE = 5;
    private final Screen parent;
    private final ClientHistoryStore history;
    private final Supplier<Optional<BlockBox>> selection;
    private final ZoneScreenController controller;
    private final Consumer<HistorySnapshotPayload.ZoneView> openDetails;
    private final Consumer<UUID> enter;
    private final Consumer<UUID> leave;
    private HistorySnapshotPayload snapshot;
    private EditBox name;
    private Button create;
    private int panelX;
    private int panelY;
    private int page;
    private String error = "";

    public LumiZonesScreen(
            Screen parent,
            ClientHistoryStore history,
            Supplier<Optional<BlockBox>> selection,
            ZoneScreenController controller,
            Consumer<HistorySnapshotPayload.ZoneView> openDetails,
            Consumer<UUID> enter,
            Consumer<UUID> leave) {
        super(Component.translatable("luma.tab.zones"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.selection = Objects.requireNonNull(selection, "selection");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.openDetails = Objects.requireNonNull(openDetails, "openDetails");
        this.enter = Objects.requireNonNull(enter, "enter");
        this.leave = Objects.requireNonNull(leave, "leave");
    }

    @Override
    protected void init() {
        snapshot = history.state().snapshot().orElse(null);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        name = new EditBox(font, contentX, panelY + 72, contentWidth - 140, 20,
                Component.translatable("luma.zones.create_title"));
        name.setMaxLength(ZoneScreenController.MAX_NAME_LENGTH);
        name.setHint(Component.translatable("luma.zones.create_title"));
        name.setResponder(value -> updateCreateButton());
        addRenderableWidget(name);
        create = addRenderableWidget(Button.builder(
                Component.translatable("luma.zones.create_button"), ignored -> create())
                .bounds(contentX + contentWidth - 132, panelY + 72, 132, 20).build());
        updateCreateButton();
        addZoneRows(panelWidth);
        addRenderableWidget(Button.builder(
                Component.literal("<"), ignored -> changePage(-1))
                .bounds(panelX + 20, panelY + 298, 28, 20).build()).active = page > 0;
        int count = snapshot == null ? 0 : snapshot.zones().size();
        addRenderableWidget(Button.builder(
                Component.literal(">"), ignored -> changePage(1))
                .bounds(panelX + 52, panelY + 298, 28, 20).build())
                .active = (page + 1) * PAGE_SIZE < count;
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 140, panelY + 298, 120, 20).build());
    }

    private void addZoneRows(int panelWidth) {
        if (snapshot == null) return;
        int start = Math.min(page * PAGE_SIZE, snapshot.zones().size());
        int end = Math.min(start + PAGE_SIZE, snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = panelY + 126 + (index - start) * 32;
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.open_details"),
                    ignored -> openDetails.accept(zone))
                    .bounds(panelX + panelWidth - 196, rowY + 4, 72, 20).build());
            addRenderableWidget(Button.builder(
                    Component.translatable(zone.active()
                            ? "luma.zones.leave" : "luma.zones.enter"),
                    ignored -> select(zone))
                    .bounds(panelX + panelWidth - 116, rowY + 4, 96, 20).build());
        }
    }

    private void updateCreateButton() {
        if (create != null && name != null) {
            create.active = !name.getValue().trim().isEmpty() && selection.get().isPresent();
        }
    }

    private void create() {
        ZoneScreenController.Submission submission =
                controller.create(name.getValue(), selection.get());
        error = submission.error();
        if (submission.accepted()) {
            feedback("luma.status.zone_created");
            minecraft.setScreen(parent);
        }
    }

    private void select(HistorySnapshotPayload.ZoneView zone) {
        try {
            (zone.active() ? leave : enter).accept(zone.id());
            feedback(zone.active()
                    ? "luma.status.zone_cleared" : "luma.status.zone_selected");
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi zone could not be updated" : failed.getMessage();
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
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        Component heading = snapshot == null
                ? title : Component.translatable(
                        "luma.screen.zones.title", snapshot.workspaceName());
        graphics.drawCenteredString(font, heading, width / 2, panelY + 16, 0xffffffff);
        graphics.drawCenteredString(font, activeZoneText(),
                width / 2, panelY + 36, 0xffaeb6c2);
        graphics.drawString(font, Component.translatable("luma.zones.create_help"),
                panelX + 20, panelY + 56, 0xffaeb6c2, false);
        graphics.drawString(font, Component.translatable("luma.zones.list_title"),
                panelX + 20, panelY + 106, 0xfff0f3f6, false);
        renderZoneRows(graphics, panelWidth);
        if (!error.isEmpty()) {
            Component text = error.startsWith("luma.")
                    ? Component.translatable(error) : Component.literal(error);
            graphics.drawString(font, text, panelX + 88, panelY + 304,
                    0xffff6b6b, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
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
                    panelX + 20, panelY + 134, 0xff8f9aa8, false);
            return;
        }
        int start = Math.min(page * PAGE_SIZE, snapshot.zones().size());
        int end = Math.min(start + PAGE_SIZE, snapshot.zones().size());
        for (int index = start; index < end; index++) {
            HistorySnapshotPayload.ZoneView zone = snapshot.zones().get(index);
            int rowY = panelY + 126 + (index - start) * 32;
            graphics.fill(panelX + 20, rowY,
                    panelX + panelWidth - 20, rowY + 28, 0xff20252c);
            graphics.drawString(font,
                    font.plainSubstrByWidth(zone.name(), panelWidth - 250),
                    panelX + 28, rowY + 5, zone.color(), false);
            graphics.drawString(font, Component.translatable(
                            "luma.zones.zone_meta",
                            Component.translatable(zone.active()
                                    ? "luma.zones.active" : "luma.zones.no_zone"),
                            zone.cells()),
                    panelX + 28, rowY + 17, 0xff8f9aa8, false);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
