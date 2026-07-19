package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Zone-scoped Save form and bounded hidden history view. */
public final class LumiZoneDetailsScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 330;
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final HistorySnapshotPayload snapshot;
    private final HistorySnapshotPayload.ZoneView zone;
    private final ZoneDetailsController controller;
    private final ClientVersionPreviewStore previews;
    private final ZoneHistoryActions actions;
    private final ZoneHistoryController zoneHistory;
    private final HistoryViewController historyView = new HistoryViewController();
    private final HistoryGraphLayout graphLayout = new HistoryGraphLayout();
    private final Runnable showChanges;
    private final VersionCompareController compareController = new VersionCompareController();
    private EditBox message;
    private EditBox tags;
    private EditBox search;
    private LumiLegacyButton save;
    private LumiLegacyButton amend;
    private int panelX;
    private int panelY;
    private String messageValue = "";
    private String tagsValue = "";
    private String searchValue = "";
    private String error = "";
    private boolean historyRequested;
    private boolean searchDirty;
    private boolean focusSearchAfterInit;
    private HistoryPagePayload renderedPage;
    private LumiHistoryGraphView graphView;

    public LumiZoneDetailsScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.ZoneView zone,
            ZoneDetailsController controller,
            ClientHistoryPageStore pages,
            ZoneHistoryController.Requester requestPage,
            ClientVersionPreviewStore previews,
            ZoneHistoryActions actions,
            Runnable showChanges) {
        super(Component.translatable("luma.zones.details_title", zone.name()));
        this.parent = parent;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.actions = Objects.requireNonNull(actions, "actions");
        zoneHistory = new ZoneHistoryController(
                snapshot, zone.id(), pages, requestPage);
        this.showChanges = Objects.requireNonNull(showChanges, "showChanges");
    }

    @Override
    public void tick() {
        super.tick();
        HistoryPagePayload latest = zoneHistory.page().orElse(null);
        if (!Objects.equals(renderedPage, latest) || searchDirty) {
            renderedPage = latest;
            searchDirty = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, panelX + 20, panelY + 68, contentWidth - 200, 20,
                Component.translatable("luma.zones.save_input"));
        message.setMaxLength(ZoneDetailsController.MAX_MESSAGE_LENGTH);
        message.setHint(Component.translatable("luma.zones.save_input"));
        message.setBordered(false);
        message.setTextColor(LegacyLumiTheme.TEXT);
        message.setValue(messageValue);
        message.setResponder(value -> {
            messageValue = value;
            updateActions();
        });
        addRenderableWidget(message);
        save = addLegacyButton(panelX + panelWidth - 212, panelY + 68, 92,
                Component.translatable("luma.zones.save_button"),
                this::save, LumiLegacyButton.Kind.PRIMARY);
        amend = addLegacyButton(panelX + panelWidth - 112, panelY + 68, 92,
                Component.translatable("luma.action.amend_version"),
                this::amend, LumiLegacyButton.Kind.NORMAL);
        tags = new EditBox(
                font, panelX + 20, panelY + 96, contentWidth - 40, 20,
                Component.translatable("luma.history.tags_input"));
        tags.setMaxLength(io.github.lumi.domain.model.VersionTags.MAX_SERIALIZED_LENGTH);
        tags.setHint(Component.translatable("luma.history.tags_input"));
        tags.setBordered(false);
        tags.setTextColor(LegacyLumiTheme.TEXT);
        tags.setValue(tagsValue);
        tags.setResponder(value -> tagsValue = value);
        addRenderableWidget(tags);
        addLegacyIconButton(panelX + panelWidth - 52, panelY + 96,
                "see-changes", Component.translatable("luma.action.see_changes"),
                () -> {
                    showChanges.run();
                    minecraft.setScreen(null);
                }, LumiLegacyButton.Kind.NORMAL).active = zone.active();
        updateActions();
        if (!historyRequested) {
            historyRequested = true;
            zoneHistory.request();
        }
        addHistoryToolbar(panelWidth);
        addHistoryButtons(panelWidth);
        LumiLegacyButton previous = addLegacyButton(
                panelX + 20, panelY + 298, 28, Component.literal("<"),
                this::previousPage, LumiLegacyButton.Kind.NORMAL);
        previous.active = zoneHistory.hasPrevious();
        LumiLegacyButton next = addLegacyButton(
                panelX + 52, panelY + 298, 28, Component.literal(">"),
                this::nextPage, LumiLegacyButton.Kind.NORMAL);
        next.active = zoneHistory.hasNext();
        addLegacyButton(panelX + panelWidth - 140, panelY + 298, 120,
                Component.translatable("luma.action.close"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void addHistoryToolbar(int panelWidth) {
        addLegacyIconButton(panelX + 20, panelY + 124,
                "unordered-list", Component.translatable("luma.history.view_cards"),
                () -> showMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + 52, panelY + 124,
                "graph", Component.translatable("luma.history.view_graph"),
                () -> showMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(panelX + 84, panelY + 124, 112,
                Component.literal(shortBranch()),
                () -> {
                    zoneHistory.nextBranch(snapshot.branches());
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.NORMAL);
        search = new EditBox(
                font, panelX + panelWidth - 160, panelY + 126, 140, 18,
                Component.translatable("luma.dashboard.search"));
        search.setBordered(false);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(searchValue);
        search.setResponder(value -> {
            searchValue = value;
            searchDirty = true;
            focusSearchAfterInit = true;
        });
        addRenderableWidget(search);
    }

    private void addHistoryButtons(int panelWidth) {
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (historyView.mode() == HistoryViewController.Mode.GRAPH) {
            graphView = new LumiHistoryGraphView(
                    snapshot.dimensionId(), previews,
                    graphLayout.build(versions, snapshot.branches()),
                    snapshot.zones(), panelX + 20, panelY + 154,
                    panelWidth - 40);
            graphView.buttons(actions.openDetails())
                    .forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < versions.size(); index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 154 + index * 44;
            int right = panelX + panelWidth - 24;
            compareController.target(versions, index).ifPresent(target ->
                    addLegacyIconButton(right - 120, rowY + 5, "see-changes",
                            Component.translatable("luma.action.compare"),
                            () -> actions.openCompare().accept(target),
                            LumiLegacyButton.Kind.NORMAL));
            addLegacyIconButton(right - 88, rowY + 5, "eye-open",
                    Component.translatable("luma.action.open_details"),
                    () -> actions.openDetails().accept(version),
                    LumiLegacyButton.Kind.NORMAL);
            addLegacyIconButton(right - 56, rowY + 5, "rollback",
                    Component.translatable("luma.action.restore"),
                    () -> actions.openRestore().accept(version),
                    LumiLegacyButton.Kind.PRIMARY);
            addLegacyIconButton(right - 24, rowY + 5, "branch",
                    Component.translatable("luma.action.create_idea"),
                    () -> actions.createBranch().accept(version),
                    LumiLegacyButton.Kind.NORMAL);
        }
    }

    private void save() {
        ZoneDetailsController.Submission submission =
                controller.save(zone.id(), messageValue, tagsValue, zone.active());
        complete(submission, "luma.status.save_started");
    }

    private void amend() {
        HistorySnapshotPayload.Version head = zone.versions().getFirst();
        String amendMessage = messageValue.isBlank() ? head.message() : messageValue;
        String amendTags = messageValue.isBlank() && tagsValue.isBlank()
                ? head.tags().serialize() : tagsValue;
        ZoneDetailsController.Submission submission =
                controller.amend(zone.id(), amendMessage, amendTags, zone.active());
        complete(submission, "luma.status.amend_started");
    }

    private void complete(ZoneDetailsController.Submission submission, String status) {
        error = submission.error();
        if (submission.accepted()) {
            feedback(status);
            minecraft.setScreen(parent);
        }
    }

    private void updateActions() {
        if (save != null) {
            save.active = zone.active() && !messageValue.trim().isEmpty();
        }
        if (amend != null) {
            amend.active = zone.active() && !zone.versions().isEmpty();
        }
    }

    private List<HistorySnapshotPayload.Version> visibleVersions() {
        return historyView.filtered(
                zoneHistory.versions(zone.versions()), searchValue);
    }

    private void showMode(HistoryViewController.Mode mode) {
        historyView.show(mode);
        rebuildWidgets();
    }

    private void previousPage() {
        zoneHistory.previous();
        rebuildWidgets();
    }

    private void nextPage() {
        zoneHistory.next();
        rebuildWidgets();
    }

    private String shortBranch() {
        String value = zoneHistory.branch().value();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private void feedback(String key) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    @Override
    protected void setInitialFocus() {
        if (focusSearchAfterInit && search != null) {
            setInitialFocus(search);
            search.setFocused(true);
            search.moveCursorToEnd(false);
            focusSearchAfterInit = false;
        } else if (message != null) {
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
                panelX + 20, panelY + 52, LegacyLumiTheme.MUTED, false);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 66,
                panelWidth - 236, 24,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 94,
                panelWidth - 76, 24,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        if (search != null) {
            LegacyLumiTheme.outlined(
                    graphics, search.getX() - 2, search.getY() - 2,
                    search.getWidth() + 4, search.getHeight() + 4,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        }
        renderHistory(graphics, panelWidth);
        String shownError = error.isEmpty() ? zoneHistory.error() : error;
        if (!shownError.isEmpty()) {
            graphics.drawString(font, errorText(shownError),
                    panelX + 88, panelY + 304, LegacyLumiTheme.DANGER, false);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        if (graphView != null) {
            graphView.renderHover(
                    graphics, font, render.mouseX(), render.mouseY());
        }
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderHistory(GuiGraphics graphics, int panelWidth) {
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (versions.isEmpty()) {
            graphics.drawString(font, Component.translatable("luma.zones.history_empty"),
                    panelX + 20, panelY + 158, LegacyLumiTheme.MUTED, false);
            return;
        }
        if (graphView != null) {
            graphView.renderConnections(graphics);
            return;
        }
        for (int index = 0; index < versions.size(); index++) {
            HistorySnapshotPayload.Version version = versions.get(index);
            int rowY = panelY + 154 + index * 44;
            renderLegacyPanel(graphics,
                    panelX + 20, rowY, panelWidth - 40, 38);
            drawPreview(graphics, version, panelX + 26, rowY + 8);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 238),
                    panelX + 72, rowY + 7, LegacyLumiTheme.TEXT, false);
            String tagText = version.tags().isEmpty()
                    ? "" : " · #" + String.join(" #", version.tags().values());
            String meta = version.author() + " · "
                    + HISTORY_TIME.format(
                            Instant.ofEpochMilli(version.timestampMillis()))
                    + " · " + version.statistics().blocks() + " blocks"
                    + tagText;
            graphics.drawString(font,
                    font.plainSubstrByWidth(meta, panelWidth - 238),
                    panelX + 72, rowY + 21, LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x,
            int y) {
        var texture = previews.texture(snapshot.dimensionId(), version.id())
                .orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, 40, 22,
                    texture.width(), texture.height(),
                    texture.width(), texture.height());
            return;
        }
        LegacyLumiTheme.outlined(
                graphics, x, y, 40, 22,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.INSET_BORDER);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
