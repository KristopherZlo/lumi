package io.github.lumi.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Full page zone-scoped Save form and history view. */
public final class LumiZoneDetailsScreen extends LumiLegacyPageScreen {
    private static final int PANEL_INSET = 6;
    private static final int CONTROL_GAP = 4;
    private static final int ICON_WIDTH = 26;
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
    private EditBox message;
    private EditBox tags;
    private EditBox search;
    private LumiLegacyButton save;
    private LumiLegacyButton amend;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private ZoneDetailsGeometry geometry;
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
    private final Map<CommitId, VersionTags> optimisticTags = new HashMap<>();

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
        geometry = zoneDetailsGeometry(panelWidth, panelHeight);
        message = new EditBox(
                font,
                panelX + geometry.messageX() + 2,
                panelY + geometry.messageFieldY() + 3,
                Math.max(0, geometry.messageWidth() - 4), INPUT_HEIGHT,
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
        addSaveActions();
        tags = new EditBox(
                font,
                panelX + geometry.tagsX() + 2,
                panelY + geometry.tagsFieldY() + 3,
                Math.max(0, geometry.tagsWidth() - 4), INPUT_HEIGHT,
                Component.translatable("luma.history.tags_input"));
        tags.setMaxLength(io.github.lumi.domain.model.VersionTags.MAX_SERIALIZED_LENGTH);
        tags.setHint(Component.translatable("luma.history.tags_input"));
        tags.setBordered(false);
        tags.setTextColor(LegacyLumiTheme.TEXT);
        tags.setValue(tagsValue);
        tags.setResponder(value -> tagsValue = value);
        addRenderableWidget(tags);
        addLegacyIconButton(
                panelX + geometry.innerRight() - ICON_WIDTH,
                panelY + geometry.tagsFieldY() + 1,
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
        addHistoryToolbar();
        addHistoryButtons();
    }

    private void addSaveActions() {
        Component saveLabel = Component.translatable("luma.zones.save_button");
        Component amendLabel = Component.translatable("luma.action.amend_version");
        int maximum = geometry.actionButtonMaximum();
        int saveWidth = LumiLegacyButton.contentWidth(maximum, saveLabel);
        int amendWidth = LumiLegacyButton.contentWidth(maximum, amendLabel);
        int groupWidth = saveWidth + CONTROL_GAP + amendWidth;
        int x = geometry.stacked()
                ? panelX + geometry.innerLeft()
                : panelX + geometry.innerRight() - groupWidth;
        save = addLegacyButton(x, panelY + geometry.actionY(), saveWidth,
                saveLabel, this::save, LumiLegacyButton.Kind.PRIMARY);
        amend = addLegacyButton(x + saveWidth + CONTROL_GAP,
                panelY + geometry.actionY(), amendWidth,
                amendLabel, this::amend, LumiLegacyButton.Kind.NORMAL);
    }

    private void addHistoryToolbar() {
        int tabX = panelX + geometry.innerLeft();
        int tabRight = panelX + geometry.innerRight();
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (tabRight - tabX < 24) break;
            LumiLegacyButton tab = addLegacyContentButton(
                    tabX, panelY + geometry.tabsY(), tabRight - tabX,
                    Component.literal(shortBranch(branch.name())),
                    () -> selectBranch(branch.name()),
                    zoneHistory.branch().value().equals(branch.name())
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
            tabX += tab.getWidth() + 4;
        }
        addLegacyIconButton(
                panelX + geometry.innerRight() - 56,
                panelY + geometry.toolbarY() + 1,
                "unordered-list", Component.translatable("luma.history.view_cards"),
                () -> showMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(
                panelX + geometry.innerRight() - ICON_WIDTH,
                panelY + geometry.toolbarY() + 1,
                "graph", Component.translatable("luma.history.view_graph"),
                () -> showMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        search = new EditBox(
                font,
                panelX + geometry.innerLeft() + 2,
                panelY + geometry.toolbarY() + 3,
                Math.max(0, geometry.searchWidth() - 4), INPUT_HEIGHT,
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

    private void addHistoryButtons() {
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
                    snapshot.zones(),
                    panelX + geometry.innerLeft(),
                    panelY + geometry.historyY(),
                    geometry.innerWidth());
            graphView.buttons(actions.openDetails())
                    .forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = panelY + geometry.historyY()
                    + index * geometry.rowStride();
            int right = panelX + geometry.innerRight();
            int actionY = rowY + (geometry.rowHeight() - 18) / 2;
            addLegacyIconButton(right - 116, actionY,
                    "rollback",
                    Component.translatable("luma.action.restore"),
                    () -> actions.openRestore().accept(version),
                    LumiLegacyButton.Kind.PRIMARY);
            addLegacyIconButton(right - 86, actionY, "folder",
                    Component.translatable("luma.action.open_details"),
                    () -> actions.openDetails().accept(version),
                    LumiLegacyButton.Kind.NORMAL);
            addLegacyIconButton(right - 56, actionY, "branch",
                    Component.translatable("luma.action.create_idea"),
                    () -> actions.createBranch().accept(version),
                    LumiLegacyButton.Kind.NORMAL);
            addLegacyIconButton(right - ICON_WIDTH, actionY, "tags",
                    Component.translatable("luma.action.edit_tags"),
                    () -> editTags(version), LumiLegacyButton.Kind.NORMAL);
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

    private void editTags(HistorySnapshotPayload.Version version) {
        minecraft.setScreen(new LumiVersionTagsScreen(
                this, displayedTags(version), replacement -> {
                    actions.updateTags().accept(version.id(), replacement);
                    optimisticTags.put(version.id(), replacement);
                }));
    }

    private VersionTags displayedTags(
            HistorySnapshotPayload.Version version) {
        return optimisticTags.getOrDefault(version.id(), version.tags());
    }

    private static String shortBranch(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private int visibleHistoryRows() {
        return visibleHistoryRows(geometry, Integer.MAX_VALUE);
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
        String shownError = error.isEmpty() ? zoneHistory.error() : error;
        if (shownError.isEmpty() || geometry.showStatus()) {
            graphics.drawCenteredString(
                    font,
                    clippedCenteredHeader(title, panelX + panelWidth / 2,
                            panelX + PANEL_INSET,
                            panelX + panelWidth - PANEL_INSET),
                    panelX + panelWidth / 2,
                    panelY + geometry.titleY(), zone.color());
        }
        if (!shownError.isEmpty()) {
            int errorY = geometry.showStatus()
                    ? geometry.statusY() : geometry.titleY();
            graphics.drawString(
                    font,
                    clippedHeader(errorText(shownError), panelX + PANEL_INSET,
                            panelX + panelWidth - PANEL_INSET),
                    panelX + PANEL_INSET, panelY + errorY,
                    LegacyLumiTheme.DANGER, false);
        } else {
            if (geometry.showStatus()) {
                graphics.drawCenteredString(font,
                        Component.translatable(zone.active()
                                ? "luma.zones.details_active"
                                : "luma.zones.details_inactive"),
                        panelX + panelWidth / 2,
                        panelY + geometry.statusY(), LegacyLumiTheme.MUTED);
            }
            if (geometry.showSummary()) {
                var statistics = zoneStatistics();
                Component summary = statistics.<Component>map(
                                PendingStatisticsText::summary)
                        .orElseGet(() -> Component.translatable(
                                "luma.zones.save_help"));
                graphics.drawString(
                        font,
                        font.plainSubstrByWidth(
                                summary.getString(), panelWidth - 12),
                        panelX + PANEL_INSET,
                        panelY + geometry.summaryY(),
                        statistics.isPresent()
                                ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.MUTED,
                        false);
            }
        }
        LegacyLumiTheme.outlined(
                graphics,
                panelX + geometry.messageX(),
                panelY + geometry.messageFieldY(),
                geometry.messageWidth(), INPUT_FRAME_HEIGHT,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        LegacyLumiTheme.outlined(
                graphics,
                panelX + geometry.tagsX(),
                panelY + geometry.tagsFieldY(),
                geometry.tagsWidth(), INPUT_FRAME_HEIGHT,
                LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        if (search != null) {
            LegacyLumiTheme.outlined(
                    graphics,
                    panelX + geometry.innerLeft(),
                    panelY + geometry.toolbarY(),
                    geometry.searchWidth(), INPUT_FRAME_HEIGHT,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
        }
        renderHistory(graphics);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        if (graphView != null && geometry.historyHeight() >= 56) {
            graphView.renderHover(
                    graphics, font, render.mouseX(), render.mouseY());
        }
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderHistory(GuiGraphics graphics) {
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (versions.isEmpty()) {
            if (geometry.historyHeight() < 9) return;
            graphics.drawString(font, Component.translatable("luma.zones.history_empty"),
                    panelX + geometry.innerLeft(),
                    panelY + geometry.historyY() + 5,
                    LegacyLumiTheme.MUTED, false);
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
            int rowY = panelY + geometry.historyY()
                    + index * geometry.rowStride();
            LegacyLumiTheme.outlined(
                    graphics, panelX + geometry.innerLeft(), rowY,
                    geometry.innerWidth(), geometry.rowHeight(),
                    LegacyLumiTheme.INSET,
                    snapshot.head().equals(version.id())
                            ? zone.color() : LegacyLumiTheme.INSET_BORDER);
            if (geometry.showPreview()) {
                drawPreview(graphics, version,
                        panelX + geometry.innerLeft() + 6,
                        rowY + (geometry.rowHeight() - 22) / 2);
            }
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            version.message(), geometry.cardTextWidth()),
                    panelX + geometry.cardTextX(),
                    rowY + (geometry.showMeta() ? 5 : 8),
                    LegacyLumiTheme.TEXT, false);
            String tagText = displayedTags(version).isEmpty()
                    ? "" : " · #" + String.join(
                            " #", displayedTags(version).values());
            String meta = version.author() + " · "
                    + HISTORY_TIME.format(
                            Instant.ofEpochMilli(version.timestampMillis()))
                    + " · " + version.statistics().blocks() + " blocks"
                    + tagText;
            if (geometry.showMeta()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(meta, geometry.cardTextWidth()),
                        panelX + geometry.cardTextX(), rowY + 17,
                        LegacyLumiTheme.MUTED, false);
            }
        }
    }

    static ZoneDetailsGeometry zoneDetailsGeometry(
            int panelWidth, int panelHeight) {
        boolean ultraCompact = panelHeight < 200;
        boolean stacked = panelWidth < 360 || ultraCompact;
        int titleY = ultraCompact ? 5 : stacked ? 10 : 16;
        int statusY = ultraCompact ? 5 : stacked ? 25 : 36;
        int summaryY = ultraCompact ? 5 : stacked ? 25 : 52;
        int messageFieldY = ultraCompact ? 18 : stacked ? 40 : 66;
        int actionY = ultraCompact ? 40 : stacked ? 64 : 68;
        int tagsFieldY = ultraCompact ? 60 : stacked ? 86 : 94;
        int tabsY = ultraCompact ? 82 : stacked ? 110 : 124;
        int toolbarY = ultraCompact ? 102 : stacked ? 132 : 148;
        int historyY = ultraCompact ? 124 : stacked ? 154 : 174;
        int rowHeight = ultraCompact ? 24 : 30;
        int rowStride = ultraCompact ? 28 : 34;
        return new ZoneDetailsGeometry(
                Math.max(0, panelWidth), Math.max(0, panelHeight),
                titleY, statusY, summaryY,
                messageFieldY, actionY, tagsFieldY,
                tabsY, toolbarY, Math.min(panelHeight, historyY),
                rowHeight, rowStride,
                stacked, !ultraCompact, !stacked,
                panelWidth >= 240, !ultraCompact);
    }

    static int visibleHistoryRows(
            ZoneDetailsGeometry geometry, int availableVersions) {
        if (geometry.historyHeight() < geometry.rowHeight()) return 0;
        return Math.min(availableVersions,
                1 + (geometry.historyHeight() - geometry.rowHeight())
                        / geometry.rowStride());
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
                && y >= panelY + geometry.historyY()
                && y < panelY + geometry.historyY() + geometry.historyHeight()) {
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

    record ZoneDetailsGeometry(
            int panelWidth,
            int panelHeight,
            int titleY,
            int statusY,
            int summaryY,
            int messageFieldY,
            int actionY,
            int tagsFieldY,
            int tabsY,
            int toolbarY,
            int historyY,
            int rowHeight,
            int rowStride,
            boolean stacked,
            boolean showStatus,
            boolean showSummary,
            boolean showPreview,
            boolean showMeta) {
        int innerLeft() { return PANEL_INSET; }
        int innerRight() { return Math.max(innerLeft(), panelWidth - PANEL_INSET); }
        int innerWidth() { return Math.max(0, innerRight() - innerLeft()); }
        int messageX() { return innerLeft(); }
        int messageWidth() {
            return stacked ? innerWidth() : Math.max(0, innerWidth() - 194);
        }
        int tagsX() { return innerLeft(); }
        int tagsWidth() {
            return Math.max(0, innerWidth() - ICON_WIDTH - CONTROL_GAP);
        }
        int searchWidth() { return Math.max(0, innerWidth() - 62); }
        int actionButtonMaximum() {
            return stacked ? Math.max(0, (innerWidth() - CONTROL_GAP) / 2) : 92;
        }
        int historyHeight() { return Math.max(0, panelHeight - historyY); }
        int cardActionX() { return innerRight() - 116; }
        int cardTextX() { return innerLeft() + 6 + (showPreview ? 46 : 0); }
        int cardTextWidth() {
            return Math.max(0, cardActionX() - CONTROL_GAP - cardTextX());
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
