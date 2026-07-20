package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientPendingStatisticsStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.PendingStatisticsPayload;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Dashboard-equivalent history page scoped to one zone. */
public final class LumiZoneDetailsScreen extends LumiLegacyPageScreen {
    private final Screen parent;
    private final HistorySnapshotPayload snapshot;
    private final HistorySnapshotPayload.ZoneView zone;
    private final ClientVersionPreviewStore previews;
    private final ZoneHistoryActions actions;
    private final ZoneHistoryController zoneHistory;
    private final ClientPendingStatisticsStore pendingStatistics;
    private final Runnable requestPendingStatistics;
    private final Runnable openSave;
    private final Runnable openAmend;
    private final Runnable showChanges;
    private final HistoryViewController historyView = new HistoryViewController();
    private final HistoryGraphLayout graphLayout = new HistoryGraphLayout();
    private final Map<CommitId, VersionTags> optimisticTags = new HashMap<>();
    private LegacyWorkspaceLayout layout;
    private LumiDashboardScreen.DashboardGeometry geometry;
    private EditBox search;
    private String searchValue = "";
    private int historyScroll;
    private boolean historyRequested;
    private boolean searchDirty;
    private boolean focusSearchAfterInit;
    private HistoryPagePayload renderedPage;
    private PendingStatisticsPayload renderedStatistics;
    private LumiHistoryGraphView graphView;
    private LumiCommitCard commitCards;

    public LumiZoneDetailsScreen(
            Screen parent,
            HistorySnapshotPayload snapshot,
            HistorySnapshotPayload.ZoneView zone,
            ClientHistoryPageStore pages,
            ZoneHistoryController.Requester requestPage,
            ClientPendingStatisticsStore pendingStatistics,
            Runnable requestPendingStatistics,
            ClientVersionPreviewStore previews,
            ZoneHistoryActions actions,
            Runnable openSave,
            Runnable openAmend,
            Runnable showChanges) {
        super(parent, Component.translatable(
                "luma.zones.details_title", zone.name()), LegacyProjectTab.ZONES);
        this.parent = parent;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.pendingStatistics = Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
        this.requestPendingStatistics = Objects.requireNonNull(
                requestPendingStatistics, "requestPendingStatistics");
        this.openSave = Objects.requireNonNull(openSave, "openSave");
        this.openAmend = Objects.requireNonNull(openAmend, "openAmend");
        this.showChanges = Objects.requireNonNull(showChanges, "showChanges");
        zoneHistory = new ZoneHistoryController(
                snapshot, zone.id(), pages, requestPage);
    }

    @Override
    public void tick() {
        super.tick();
        HistoryPagePayload latest = zoneHistory.page().orElse(null);
        PendingStatisticsPayload statistics =
                pendingStatistics.result(snapshot).orElse(null);
        if (!Objects.equals(renderedPage, latest)
                || !Objects.equals(renderedStatistics, statistics)
                || searchDirty) {
            renderedPage = latest;
            renderedStatistics = statistics;
            searchDirty = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        layout = pageLayout();
        geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), 0);
        commitCards = new LumiCommitCard(font, previews, snapshot.dimensionId());
        requestPendingStatistics();
        addWorkspaceActions();
        if (!historyRequested) {
            historyRequested = true;
            zoneHistory.request();
        }
        renderedPage = zoneHistory.page().orElse(null);
        renderedStatistics = pendingStatistics.result(snapshot).orElse(null);
        latestCreated().filter(ignored -> geometry.latestVisible())
                .ifPresent(version -> addVersionActions(
                        version, LumiDashboardScreen.latestCardY(geometry)));
        if (geometry.historyHeight() < 28) return;
        addHistoryToolbar();
        addHistoryActions();
    }

    private void addWorkspaceActions() {
        int x = layout.bodyX() + LumiDashboardScreen.PANEL_PADDING;
        int available = Math.max(0,
                layout.bodyWidth() - LumiDashboardScreen.PANEL_PADDING * 2);
        int maximumTextWidth = Math.max(0,
                (available - LumiDashboardScreen.ICON_BUTTON_WIDTH
                        - LumiDashboardScreen.CONTROL_GAP * 2) / 2);
        LumiLegacyButton save = addLegacyContentButton(
                x, geometry.actionY(), maximumTextWidth,
                Component.translatable("luma.zones.save_button"), openSave,
                LumiLegacyButton.Kind.PRIMARY);
        save.active = zone.active();
        x += save.getWidth() + LumiDashboardScreen.CONTROL_GAP;
        LumiLegacyButton amend = addLegacyContentButton(
                x, geometry.actionY(), maximumTextWidth,
                Component.translatable("luma.action.amend_version"), openAmend,
                LumiLegacyButton.Kind.NORMAL);
        amend.active = zone.active() && !zone.versions().isEmpty();
        x += amend.getWidth() + LumiDashboardScreen.CONTROL_GAP;
        LumiLegacyButton changes = addLegacyIconButton(
                x, geometry.actionY(), "see-changes",
                Component.translatable("luma.action.see_changes"), () -> {
                    showChanges.run();
                    minecraft.setScreen(null);
                }, LumiLegacyButton.Kind.NORMAL);
        changes.active = zone.active();
    }

    private void addHistoryToolbar() {
        int left = layout.bodyX() + LumiDashboardScreen.PANEL_PADDING;
        int right = layout.bodyX() + layout.bodyWidth()
                - LumiDashboardScreen.PANEL_PADDING;
        int toolbarY = geometry.historyY()
                + LumiDashboardScreen.HISTORY_TOOLBAR_OFFSET;
        addLegacyIconButton(right - 56, toolbarY,
                "unordered-list", Component.translatable("luma.history.view_cards"),
                () -> showMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(right - 26, toolbarY,
                "graph", Component.translatable("luma.history.view_graph"),
                () -> showMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        int searchWidth = Math.min(100, Math.max(70, layout.bodyWidth() / 4));
        search = addLegacyTextField(
                left, toolbarY, searchWidth,
                Component.translatable("luma.dashboard.search"));
        search.setMaxLength(HistoryPageRequestPayload.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(searchValue);
        search.setResponder(this::search);
        addBranchTabs(left + searchWidth + LumiDashboardScreen.CONTROL_GAP,
                right - 60, toolbarY);
        if (focusSearchAfterInit) {
            setInitialFocus(search);
            search.setFocused(true);
            search.moveCursorToEnd(false);
            focusSearchAfterInit = false;
        }
    }

    private void addBranchTabs(int x, int right, int y) {
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (right - x < 24) break;
            LumiLegacyButton tab = addLegacyContentButton(
                    x, y, right - x,
                    Component.literal(shortBranch(branch.name())),
                    () -> selectBranch(branch.name()),
                    zoneHistory.branch().value().equals(branch.name())
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
            x += tab.getWidth() + LumiDashboardScreen.CONTROL_GAP;
        }
    }

    private void addHistoryActions() {
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
                    layout.bodyX() + LumiDashboardScreen.PANEL_PADDING,
                    geometry.historyY()
                            + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET,
                    layout.bodyWidth() - LumiDashboardScreen.PANEL_PADDING * 2);
            graphView.buttons(actions.openDetails())
                    .forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            addVersionActions(
                    visible.get(index),
                    geometry.historyY()
                            + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET
                            + index * LumiDashboardScreen.historyRowStride(
                                    layout.bodyWidth()));
        }
    }

    private void addVersionActions(
            HistorySnapshotPayload.Version version, int rowY) {
        LumiCommitCard.Layout card = LumiDashboardScreen.versionCardLayout(
                layout.bodyX(), layout.bodyWidth(), rowY);
        addLegacyIconButton(card.actionX(0), card.actionY(),
                "rollback", Component.translatable("luma.action.restore"),
                () -> actions.openRestore().accept(version),
                LumiLegacyButton.Kind.PRIMARY);
        addLegacyIconButton(card.actionX(1), card.actionY(),
                "folder", Component.translatable("luma.action.open_details"),
                () -> actions.openDetails().accept(version),
                LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(card.actionX(2), card.actionY(),
                "branch", Component.translatable("luma.action.create_idea"),
                () -> actions.createBranch().accept(version),
                LumiLegacyButton.Kind.NORMAL);
        addLegacyIconButton(card.actionX(3), card.actionY(),
                "tags", Component.translatable("luma.action.edit_tags"),
                () -> editTags(version), LumiLegacyButton.Kind.NORMAL);
    }

    private void search(String value) {
        if (searchValue.equals(value)) return;
        searchValue = value;
        zoneHistory.search(value);
        searchDirty = true;
        focusSearchAfterInit = true;
    }

    private void selectBranch(String branch) {
        zoneHistory.selectBranch(branch);
        historyScroll = 0;
        rebuildWidgets();
    }

    private void showMode(HistoryViewController.Mode mode) {
        historyView.show(mode);
        rebuildWidgets();
    }

    private void editTags(HistorySnapshotPayload.Version version) {
        minecraft.setScreen(new LumiVersionTagsScreen(
                this, displayedTags(version), replacement -> {
                    actions.updateTags().accept(version.id(), replacement);
                    optimisticTags.put(version.id(), replacement);
                }));
    }

    private VersionTags displayedTags(HistorySnapshotPayload.Version version) {
        return optimisticTags.getOrDefault(version.id(), version.tags());
    }

    private Optional<HistorySnapshotPayload.Version> latestCreated() {
        return zone.versions().stream().max(Comparator.comparingLong(
                HistorySnapshotPayload.Version::timestampMillis));
    }

    private List<HistorySnapshotPayload.Version> visibleVersions() {
        return historyView.filtered(
                zoneHistory.versions(zone.versions()), searchValue);
    }

    private int visibleHistoryRows() {
        return LumiDashboardScreen.visibleHistoryRows(
                geometry.historyHeight(), Integer.MAX_VALUE, layout.bodyWidth());
    }

    private static String shortBranch(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(
                    graphics, layout.contentX(), layout.windowY(),
                    layout.contentWidth(), layout.windowHeight());
            renderPageHeader(
                    graphics, layout.contentX(), layout.windowY(),
                    layout.contentWidth(), title,
                    Component.translatable(zone.active()
                            ? "luma.zones.details_active"
                            : "luma.zones.details_inactive"));
            renderWorkspace(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
            if (graphView != null && geometry.historyHeight() >= 56) {
                graphView.renderHover(
                        graphics, font, render.mouseX(), render.mouseY());
            }
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderWorkspace(GuiGraphics graphics) {
        int x = layout.bodyX();
        int width = layout.bodyWidth();
        renderLegacyPanel(
                graphics, x, layout.bodyY(), width, geometry.buildPanelHeight());
        if (geometry.headerVisible()) {
            int titleOffset = geometry.compact() ? 8 : 13;
            int statusOffset = geometry.compact() ? 23 : 31;
            graphics.drawString(font,
                    Component.translatable("luma.zones.save_title"),
                    x + LumiDashboardScreen.PANEL_PADDING,
                    layout.bodyY() + titleOffset,
                    LegacyLumiTheme.TEXT, false);
            var statistics = zoneStatistics();
            Component status = statistics
                    .<Component>map(PendingStatisticsText::summary)
                    .orElseGet(() -> Component.translatable(
                            "luma.zones.save_help"));
            graphics.drawString(font, status,
                    x + LumiDashboardScreen.PANEL_PADDING,
                    layout.bodyY() + statusOffset,
                    statistics.isPresent()
                            ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.MUTED,
                    false);
        }
        if (geometry.latestVisible()) {
            renderLegacyPanel(graphics, x, geometry.latestY(),
                    width, geometry.latestHeight());
            graphics.drawString(font,
                    Component.translatable("luma.dashboard.latest_badge"),
                    x + LumiDashboardScreen.PANEL_PADDING,
                    geometry.latestY() + 7, LegacyLumiTheme.TEXT, false);
            latestCreated().ifPresent(version -> renderVersionCard(
                    graphics, version,
                    LumiDashboardScreen.latestCardY(geometry), true));
        }
        if (geometry.historyHeight() <= 0) return;
        renderLegacyPanel(graphics, x, geometry.historyY(),
                width, geometry.historyHeight());
        if (geometry.historyHeight() < 28) return;
        if (search != null) renderLegacyTextField(graphics, search);
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (graphView != null) graphView.renderConnections(graphics);
        int rows = LumiDashboardScreen.visibleHistoryRows(
                geometry.historyHeight(), versions.size(), layout.bodyWidth());
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(rows).toList();
        for (int index = 0; graphView == null && index < visible.size(); index++) {
            renderVersionCard(
                    graphics, visible.get(index),
                    geometry.historyY()
                            + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET
                            + index * LumiDashboardScreen.historyRowStride(
                                    layout.bodyWidth()),
                    false);
        }
        renderLegacyScrollbar(
                graphics, x + width - 6,
                geometry.historyY()
                        + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET,
                Math.max(0, geometry.historyHeight()
                        - LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET - 5),
                versions.size(), rows, historyScroll);
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable(searchValue.isBlank()
                            ? "luma.zones.history_empty"
                            : "luma.project.history_search_help"),
                    x + LumiDashboardScreen.PANEL_PADDING,
                    geometry.historyY() + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET,
                    LegacyLumiTheme.MUTED, false);
        }
    }

    private void renderVersionCard(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int rowY,
            boolean featured) {
        commitCards.render(
                graphics, version, displayedTags(version),
                LumiDashboardScreen.versionCardLayout(
                        layout.bodyX(), layout.bodyWidth(), rowY),
                zone.color(), snapshot.head().equals(version.id()), featured);
    }

    private void requestPendingStatistics() {
        if (snapshot.pendingKeys() > 0
                && pendingStatistics.result(snapshot).isEmpty()
                && !pendingStatistics.pending(snapshot)) {
            requestPendingStatistics.run();
        }
    }

    private Optional<io.github.lumi.domain.model.PendingChangeStatistics>
            zoneStatistics() {
        return pendingStatistics.result(snapshot)
                .filter(result -> result.error().isEmpty())
                .map(result -> result.zones().get(zone.id()))
                .filter(Objects::nonNull);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= layout.bodyX()
                && x < layout.bodyX() + layout.bodyWidth()
                && y >= geometry.historyY()
                        + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET
                && y < geometry.historyY() + geometry.historyHeight()) {
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
