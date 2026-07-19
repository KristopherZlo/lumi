package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
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

/** Full page zone-scoped Save form and history view. */
public final class LumiZoneDetailsScreen extends LumiLegacyPageScreen {
    private static final int HISTORY_ROW_HEIGHT = 30;
    private static final int HISTORY_ROW_STRIDE = 34;
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
    private final ClientPendingStatisticsStore pendingStatistics;
    private final Runnable requestPendingStatistics;
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
    private int panelWidth;
    private int panelHeight;
    private int historyScroll;
    private String messageValue = "";
    private String tagsValue = "";
    private String searchValue = "";
    private String error = "";
    private boolean historyRequested;
    private boolean searchDirty;
    private boolean focusSearchAfterInit;
    private HistoryPagePayload renderedPage;
    private PendingStatisticsPayload renderedStatistics;
    private LumiHistoryGraphView graphView;

    public LumiZoneDetailsScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.ZoneView zone,
            ZoneDetailsController controller,
            ClientHistoryPageStore pages,
            ZoneHistoryController.Requester requestPage,
            ClientPendingStatisticsStore pendingStatistics,
            Runnable requestPendingStatistics,
            ClientVersionPreviewStore previews,
            ZoneHistoryActions actions,
            Runnable showChanges) {
        super(parent, Component.translatable(
                "luma.zones.details_title", zone.name()), LegacyProjectTab.ZONES);
        this.parent = parent;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.pendingStatistics = Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
        this.requestPendingStatistics = Objects.requireNonNull(
                requestPendingStatistics, "requestPendingStatistics");
        zoneHistory = new ZoneHistoryController(
                snapshot, zone.id(), pages, requestPage);
        this.showChanges = Objects.requireNonNull(showChanges, "showChanges");
    }

    @Override
    public void tick() {
        super.tick();
        HistoryPagePayload latest = zoneHistory.page().orElse(null);
        PendingStatisticsPayload latestStatistics =
                pendingStatistics.result(snapshot).orElse(null);
        if (!Objects.equals(renderedPage, latest)
                || !Objects.equals(renderedStatistics, latestStatistics)
                || searchDirty) {
            renderedPage = latest;
            renderedStatistics = latestStatistics;
            searchDirty = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        requestPendingStatistics();
        LegacyWorkspaceLayout layout = pageLayout();
        panelX = layout.contentX();
        panelY = layout.windowY();
        panelWidth = layout.contentWidth();
        panelHeight = layout.windowHeight();
        int contentWidth = panelWidth - 40;
        message = new EditBox(
                font, panelX + 20, panelY + 69, contentWidth - 200, 16,
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
                font, panelX + 20, panelY + 97, contentWidth - 40, 16,
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
    }

    private void addHistoryToolbar(int panelWidth) {
        int tabX = panelX + 20;
        int tabRight = panelX + panelWidth - 20;
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (tabRight - tabX < 24) break;
            LumiLegacyButton tab = addLegacyButton(
                    tabX, panelY + 124, tabRight - tabX,
                    Component.literal(shortBranch(branch.name())),
                    () -> selectBranch(branch.name()),
                    zoneHistory.branch().value().equals(branch.name())
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
            tabX += tab.getWidth() + 4;
        }
        addLegacyIconButton(panelX + panelWidth - 84, panelY + 148,
                "unordered-list", Component.translatable("luma.history.view_cards"),
                () -> showMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(panelX + panelWidth - 52, panelY + 148,
                "graph", Component.translatable("luma.history.view_graph"),
                () -> showMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        search = new EditBox(
                font, panelX + 20, panelY + 151,
                Math.min(140, Math.max(60, panelWidth / 3)), 16,
                Component.translatable("luma.dashboard.search"));
        search.setBordered(false);
        search.setMaxLength(HistoryPageRequestPayload.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(searchValue);
        search.setResponder(value -> {
            searchValue = value;
            zoneHistory.search(value);
            searchDirty = true;
            focusSearchAfterInit = true;
        });
        addRenderableWidget(search);
    }

    private void addHistoryButtons(int panelWidth) {
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        int capacity = visibleHistoryRows();
        historyScroll = Math.min(
                historyScroll, Math.max(0, versions.size() - capacity));
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(capacity).toList();
        if (historyView.mode() == HistoryViewController.Mode.GRAPH) {
            graphView = new LumiHistoryGraphView(
                    snapshot.dimensionId(), previews,
                    graphLayout.build(visible, snapshot.branches()),
                    snapshot.zones(), panelX + 20, panelY + 176,
                    panelWidth - 40);
            graphView.buttons(actions.openDetails())
                    .forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = panelY + 176 + index * HISTORY_ROW_STRIDE;
            int right = panelX + panelWidth - 24;
            compareController.target(versions, historyScroll + index).ifPresent(target ->
                    addLegacyIconButton(right - 120, rowY + 5, "see-changes",
                            Component.translatable("luma.action.compare"),
                            () -> actions.openCompare().accept(target),
                            LumiLegacyButton.Kind.NORMAL));
            addLegacyIconButton(right - 88, rowY + 5, "rollback",
                    Component.translatable("luma.action.restore"),
                    () -> actions.openRestore().accept(version),
                    LumiLegacyButton.Kind.PRIMARY);
            addLegacyIconButton(right - 56, rowY + 5, "folder",
                    Component.translatable("luma.action.open_details"),
                    () -> actions.openDetails().accept(version),
                    LumiLegacyButton.Kind.NORMAL);
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

    private void selectBranch(String branch) {
        zoneHistory.selectBranch(branch);
        historyScroll = 0;
        rebuildWidgets();
    }

    private static String shortBranch(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private int visibleHistoryRows() {
        return Math.max(1, (panelHeight - 184) / HISTORY_ROW_STRIDE);
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
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, zone.color());
        graphics.drawCenteredString(font,
                Component.translatable(zone.active()
                        ? "luma.zones.details_active" : "luma.zones.details_inactive"),
                width / 2, panelY + 36, LegacyLumiTheme.MUTED);
        var statistics = zoneStatistics();
        graphics.drawString(
                font,
                statistics.<Component>map(PendingStatisticsText::summary)
                        .orElseGet(() -> Component.translatable(
                                "luma.zones.save_help")),
                panelX + 20, panelY + 52,
                statistics.isPresent()
                        ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.MUTED,
                false);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 66,
                panelWidth - 236, 20,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        LegacyLumiTheme.outlined(graphics, panelX + 18, panelY + 94,
                panelWidth - 76, 20,
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
                    panelX + 88, panelY + panelHeight - 14,
                    LegacyLumiTheme.DANGER, false);
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
                    panelX + 20, panelY + 180, LegacyLumiTheme.MUTED, false);
            return;
        }
        if (graphView != null) {
            graphView.renderConnections(graphics);
            return;
        }
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(visibleHistoryRows()).toList();
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = panelY + 176 + index * HISTORY_ROW_STRIDE;
            LegacyLumiTheme.outlined(
                    graphics, panelX + 20, rowY,
                    panelWidth - 40, HISTORY_ROW_HEIGHT,
                    LegacyLumiTheme.INSET,
                    index == 0 && historyScroll == 0
                            ? zone.color() : LegacyLumiTheme.INSET_BORDER);
            drawPreview(graphics, version, panelX + 26, rowY + 4);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 270),
                    panelX + 72, rowY + 5, LegacyLumiTheme.TEXT, false);
            String tagText = version.tags().isEmpty()
                    ? "" : " · #" + String.join(" #", version.tags().values());
            String meta = version.author() + " · "
                    + HISTORY_TIME.format(
                            Instant.ofEpochMilli(version.timestampMillis()))
                    + " · " + version.statistics().blocks() + " blocks"
                    + tagText;
            graphics.drawString(font,
                    font.plainSubstrByWidth(meta, panelWidth - 270),
                    panelX + 72, rowY + 17, LegacyLumiTheme.MUTED, false);
        }
    }

    private void requestPendingStatistics() {
        if (snapshot.pendingKeys() > 0
                && pendingStatistics.result(snapshot).isEmpty()
                && !pendingStatistics.pending(snapshot)) {
            requestPendingStatistics.run();
        }
        renderedStatistics = pendingStatistics.result(snapshot).orElse(null);
    }

    private java.util.Optional<io.github.lumi.domain.model.PendingChangeStatistics>
            zoneStatistics() {
        return pendingStatistics.result(snapshot)
                .filter(result -> result.error().isEmpty())
                .map(result -> result.zones().get(zone.id()))
                .filter(Objects::nonNull);
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

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= panelY + 176 && y < panelY + panelHeight) {
            int maximum = Math.max(
                    0, visibleVersions().size() - visibleHistoryRows());
            int replacement = Math.max(0, Math.min(
                    maximum, historyScroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != historyScroll) {
                historyScroll = replacement;
                rebuildWidgets();
            } else if (verticalAmount < 0 && zoneHistory.hasNext()) {
                zoneHistory.next();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
