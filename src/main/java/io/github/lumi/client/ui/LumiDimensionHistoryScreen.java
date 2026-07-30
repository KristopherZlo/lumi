package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scrollable read-only history for one loaded dimension runtime. */
public final class LumiDimensionHistoryScreen extends LumiPageScreen {
    private final String dimensionId;
    private final ClientHistoryPageStore pages;
    private final ClientVersionPreviewStore previews;
    private final Requester requester;
    private final Consumer<HistorySnapshotPayload.Version> openDetails;
    private final HistoryViewController view;
    private final HistoryGraphLayout graphLayout = new HistoryGraphLayout();
    private final List<HistorySnapshotPayload.Version> loaded = new ArrayList<>();
    private HistoryPagePayload renderedPage;
    private LumiHistoryGraphView graphView;
    private EditBox search;
    private String query = "";
    private UUID loadedRequest;
    private int scroll;
    private LumiPageLayout layout;
    private LumiDashboardScreen.DashboardGeometry geometry;
    private int historyY;
    private int historyHeight;
    private boolean requested;
    private boolean refocusSearch;
    private long observedHistoryRevision;
    private LumiCommitCard commitCards;

    public LumiDimensionHistoryScreen(
            Screen parent,
            String dimensionId,
            ClientHistoryPageStore pages,
            ClientVersionPreviewStore previews,
            Requester requester,
            Consumer<HistorySnapshotPayload.Version> openDetails) {
        super(parent, Component.translatable(
                "luma.dimensions.history_title", dimensionId),
                ProjectTab.HISTORY);
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.openDetails = Objects.requireNonNull(openDetails, "openDetails");
        observedHistoryRevision = pages.revision(dimensionId);
        view = new HistoryViewController(new HistoryScope.Dimension(dimensionId));
    }

    @Override
    protected void init() {
        beginScreenInit();
        layout = pageLayout();
        commitCards = new LumiCommitCard(font, previews, dimensionId);
        syncPage();
        if (!requested) request(0);
        geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), 0,
                latestCreated().isPresent());
        historyY = geometry.historyY();
        historyHeight = geometry.historyHeight();
        addDisabledBuildActions();
        if (geometry.latestVisible()) {
            latestCreated().ifPresent(version -> addVersionActions(
                    version, LumiDashboardScreen.latestCardY(geometry)));
        }
        if (historyHeight < 28) return;
        int right = layout.bodyX() + layout.bodyWidth()
                - LumiDashboardScreen.PANEL_PADDING;
        addIconButton(right - 56,
                historyY + LumiDashboardScreen.HISTORY_TOOLBAR_OFFSET,
                "unordered-list",
                Component.translatable("luma.history.view_cards"),
                () -> changeView(HistoryViewController.Mode.CARDS),
                view.mode() == HistoryViewController.Mode.CARDS
                        ? LumiButton.Kind.SELECTED
                        : LumiButton.Kind.NORMAL);
        addIconButton(right - 26,
                historyY + LumiDashboardScreen.HISTORY_TOOLBAR_OFFSET, "graph",
                Component.translatable("luma.history.view_graph"),
                () -> changeView(HistoryViewController.Mode.GRAPH),
                view.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiButton.Kind.SELECTED
                        : LumiButton.Kind.NORMAL);
        int currentSearchWidth = Math.min(
                100, Math.max(70, layout.bodyWidth() / 4));
        int searchX = layout.bodyX() + LumiDashboardScreen.PANEL_PADDING;
        search = addTextField(
                searchX,
                historyY + LumiDashboardScreen.HISTORY_TOOLBAR_OFFSET,
                LumiPageLayout.doubledSearchWidth(
                        currentSearchWidth,
                        Math.max(20, right - 60 - searchX
                                - LumiDashboardScreen.CONTROL_GAP)),
                Component.translatable("luma.dashboard.search"));
        search.setMaxLength(HistoryPageRequestPayload.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(query);
        search.setResponder(this::search);
        if (refocusSearch) {
            setInitialFocus(search);
            search.setFocused(true);
            search.moveCursorToEnd(false);
            refocusSearch = false;
        }
        addRows();
    }

    private void addDisabledBuildActions() {
        int x = layout.bodyX() + LumiDashboardScreen.PANEL_PADDING;
        int available = Math.max(0, layout.bodyWidth()
                - LumiDashboardScreen.PANEL_PADDING * 2);
        int maximumTextWidth = Math.max(0,
                (available - LumiDashboardScreen.ICON_BUTTON_WIDTH * 2
                        - LumiDashboardScreen.CONTROL_GAP * 3) / 2);
        LumiButton save = addContentButton(
                x, geometry.actionY(), maximumTextWidth,
                Component.translatable("luma.action.save_build"),
                () -> { }, LumiButton.Kind.PRIMARY);
        save.active = false;
        x += save.getWidth() + LumiDashboardScreen.CONTROL_GAP;
        LumiButton amend = addContentButton(
                x, geometry.actionY(), maximumTextWidth,
                Component.translatable("luma.action.amend_version"),
                () -> { }, LumiButton.Kind.NORMAL);
        amend.active = false;
        x += amend.getWidth() + LumiDashboardScreen.CONTROL_GAP;
        LumiButton changes = addIconButton(
                x, geometry.actionY(), "see-changes",
                Component.translatable("luma.action.see_changes"),
                () -> { }, LumiButton.Kind.NORMAL);
        changes.active = false;
        LumiButton rollback = addIconButton(
                x + LumiDashboardScreen.ICON_BUTTON_WIDTH
                        + LumiDashboardScreen.CONTROL_GAP,
                geometry.actionY(), "rollback",
                Component.translatable("key.lumi.quick_rollback"),
                () -> { }, LumiButton.Kind.DANGER);
        rollback.active = false;
    }

    private void addRows() {
        int capacity = capacity();
        scroll = Math.min(scroll, Math.max(0, loaded.size() - capacity));
        List<HistorySnapshotPayload.Version> visible = loaded.stream()
                .skip(scroll).limit(capacity).toList();
        if (view.mode() == HistoryViewController.Mode.GRAPH) {
            graphView = new LumiHistoryGraphView(
                    dimensionId, previews,
                    graphLayout.window(
                            graphLayout.build(loaded, List.of()), scroll, capacity),
                    List.of(),
                    layout.bodyX() + LumiDashboardScreen.PANEL_PADDING,
                    rowsY(), layout.bodyWidth()
                            - LumiDashboardScreen.PANEL_PADDING * 2);
            graphView.buttons(openDetails).forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            addVersionActions(version,
                    rowsY() + index
                            * LumiDashboardScreen.historyRowStride(
                                    layout.bodyWidth()));
        }
    }

    private void addVersionActions(
            HistorySnapshotPayload.Version version, int rowY) {
        LumiCommitCard.Layout card = LumiDashboardScreen.versionCardLayout(
                layout.bodyX(), layout.bodyWidth(), rowY);
        LumiButton restore = addIconButton(card.actionX(0), card.actionY(),
                "rollback", Component.translatable("luma.action.restore"),
                () -> { }, LumiButton.Kind.PRIMARY);
        restore.active = false;
        addIconButton(card.actionX(1), card.actionY(),
                "folder", Component.translatable("luma.action.open_details"),
                () -> openDetails.accept(version), LumiButton.Kind.NORMAL);
        LumiButton branch = addIconButton(card.actionX(2), card.actionY(),
                "branch", Component.translatable("luma.action.create_idea"),
                () -> { }, LumiButton.Kind.NORMAL);
        branch.active = false;
        LumiButton tags = addIconButton(card.actionX(3), card.actionY(),
                "tags", Component.translatable("luma.action.edit_tags"),
                () -> { }, LumiButton.Kind.NORMAL);
        tags.active = false;
    }

    @Override
    public void tick() {
        super.tick();
        long historyRevision = pages.revision(dimensionId);
        if (historyRevision != observedHistoryRevision) {
            observedHistoryRevision = historyRevision;
            loaded.clear();
            loadedRequest = null;
            renderedPage = null;
            scroll = 0;
            request(0);
            rebuildWidgets();
            return;
        }
        HistoryPagePayload latest = page().orElse(null);
        if (!Objects.equals(renderedPage, latest)) {
            refocusSearch = search != null && search.isFocused();
            renderedPage = latest;
            syncPage();
            rebuildWidgets();
        }
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderPageHeader(graphics, layout.contentX(), layout.windowY(),
                    layout.contentWidth(), title,
                    Component.translatable("luma.dimensions.read_only"));
            renderBuildPanel(graphics);
            renderLatest(graphics, render.mouseX(), render.mouseY());
            if (historyHeight > 0) {
                renderPanel(graphics, layout.bodyX(), historyY,
                        layout.bodyWidth(), historyHeight);
            }
            if (search != null) renderTextField(graphics, search);
            if (graphView != null) graphView.renderConnections(graphics);
            renderRows(graphics, render.mouseX(), render.mouseY());
            renderScrollbar(
                    graphics, layout.bodyX(), rowsY(), layout.bodyWidth() - 3,
                    Math.max(0, historyHeight
                            - LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET - 5),
                    loaded.size(), capacity(), scroll,
                    value -> scroll = value);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
            if (graphView != null) {
                graphView.renderHover(
                        graphics, font, render.mouseX(), render.mouseY());
            }
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderBuildPanel(GuiGraphics graphics) {
        renderPanel(graphics, layout.bodyX(), layout.bodyY(),
                layout.bodyWidth(), geometry.buildPanelHeight());
        if (!geometry.headerVisible()) return;
        int titleOffset = geometry.compact() ? 8 : 13;
        int statusOffset = geometry.compact() ? 23 : 31;
        graphics.drawString(font,
                Component.translatable("luma.project.build_title"),
                layout.bodyX() + LumiDashboardScreen.PANEL_PADDING,
                layout.bodyY() + titleOffset, LumiTheme.TEXT, false);
        graphics.drawString(font,
                Component.translatable("luma.dimensions.read_only"),
                layout.bodyX() + LumiDashboardScreen.PANEL_PADDING,
                layout.bodyY() + statusOffset, LumiTheme.MUTED, false);
    }

    private void renderRows(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (graphView != null) return;
        List<HistorySnapshotPayload.Version> visible = loaded.stream()
                .skip(scroll).limit(capacity()).toList();
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int y = rowsY() + index
                    * LumiDashboardScreen.historyRowStride(layout.bodyWidth());
            commitCards.render(
                    graphics, version, version.tags(),
                    LumiDashboardScreen.versionCardLayout(
                            layout.bodyX(), layout.bodyWidth(), y),
                    LumiTheme.ACCENT, false, false, mouseX, mouseY);
        }
        if (loaded.isEmpty()) {
            Optional<HistoryPagePayload> page = page();
            String error = page.map(HistoryPagePayload::error).orElse("");
            Component message = page.isEmpty()
                    ? Component.translatable("luma.dimensions.loading")
                    : !error.isEmpty() ? Component.literal(error)
                    : Component.translatable(query.isBlank()
                            ? "luma.history.empty"
                            : "luma.project.history_search_help");
            graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(
                            message.getString(),
                            Math.max(1, layout.bodyWidth() - 32)),
                    layout.bodyX() + layout.bodyWidth() / 2,
                    LumiDashboardScreen.emptyHistoryY(
                            historyY, historyHeight),
                    error.isEmpty() ? LumiTheme.MUTED : LumiTheme.DANGER);
        }
    }

    private void renderLatest(
            GuiGraphics graphics, int mouseX, int mouseY) {
        if (!geometry.latestVisible()) return;
        latestCreated().ifPresent(version -> {
            renderPanel(graphics, layout.bodyX(), geometry.latestY(),
                    layout.bodyWidth(), geometry.latestHeight());
            graphics.drawString(font,
                    Component.translatable("luma.dashboard.latest_badge"),
                    layout.bodyX() + LumiDashboardScreen.PANEL_PADDING,
                    geometry.latestY() + 7, LumiTheme.TEXT, false);
            commitCards.render(
                    graphics, version, version.tags(),
                    LumiDashboardScreen.versionCardLayout(
                            layout.bodyX(), layout.bodyWidth(),
                            LumiDashboardScreen.latestCardY(geometry)),
                    LumiTheme.ACCENT, false, true, mouseX, mouseY);
        });
    }

    private Optional<HistorySnapshotPayload.Version> latestCreated() {
        return loaded.stream().filter(VersionText::featured)
                .max(java.util.Comparator.comparingLong(
                        HistorySnapshotPayload.Version::timestampMillis));
    }

    private int rowsY() {
        return historyY + LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (layout == null
                || x < layout.bodyX()
                || x >= layout.bodyX() + layout.bodyWidth()
                || y < rowsY() || y >= historyY + historyHeight) {
            return super.mouseScrolled(
                    mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int maximum = Math.max(0, loaded.size() - capacity());
        int replacement = Math.max(
                0, Math.min(maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
        if (replacement != scroll) {
            scroll = replacement;
            rebuildWidgets();
        } else if (verticalAmount < 0
                && page().map(HistoryPagePayload::hasMore).orElse(false)) {
            request(loaded.size());
        }
        return true;
    }

    private void search(String replacement) {
        if (!query.equals(replacement.trim())) {
            query = replacement.trim();
            scroll = 0;
            request(0);
        }
    }

    private void changeView(HistoryViewController.Mode mode) {
        view.show(mode);
        rebuildWidgets();
    }

    private void request(int offset) {
        requested = true;
        requester.request(dimensionId, offset, HistoryPagePayload.MAX_VERSIONS, query);
    }

    private Optional<HistoryPagePayload> page() {
        return pages.page(
                dimensionId, HistoryPageRequestPayload.ACTIVE_WORKSPACE,
                HistoryPageRequestPayload.ACTIVE_BRANCH, Optional.empty());
    }

    private void syncPage() {
        page().filter(current -> !current.requestId().equals(loadedRequest))
                .ifPresent(current -> {
                    if (current.offset() == 0) loaded.clear();
                    if (current.error().isEmpty()) loaded.addAll(current.versions());
                    loadedRequest = current.requestId();
                    renderedPage = current;
                });
    }

    private int capacity() {
        return LumiDashboardScreen.visibleHistoryRows(
                historyHeight, loaded.size(), layout.bodyWidth());
    }

    @Override
    protected String displayedDimensionId(HistorySnapshotPayload snapshot) {
        return dimensionId;
    }

    @Override
    protected boolean rendersProjectHeader() {
        return false;
    }

    @Override public boolean isPauseScreen() { return false; }

    @FunctionalInterface
    public interface Requester {
        void request(String dimensionId, int offset, int limit, String query);
    }
}
