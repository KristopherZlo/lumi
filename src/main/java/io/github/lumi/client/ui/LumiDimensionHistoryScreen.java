package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.network.HistoryPagePayload;
import io.github.lumi.network.HistoryPageRequestPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scrollable read-only history for one loaded dimension runtime. */
public final class LumiDimensionHistoryScreen extends LumiPageScreen {
    private static final int ROW_HEIGHT = 30;
    private static final int ROW_STRIDE = 34;
    private static final int LATEST_HEIGHT = 54;
    private static final int SECTION_GAP = 5;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
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
    private int loadedOffset = -1;
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private boolean requested;
    private boolean queryDirty;
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
                ProjectTab.MORE);
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
        LumiPageLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        commitCards = new LumiCommitCard(font, previews, dimensionId);
        syncPage();
        if (!requested) request(0);
        int historyY = historyY();
        int right = panelX + panelWidth - 16;
        addIconButton(right - 58, historyY + 4, "unordered-list",
                Component.translatable("luma.history.view_cards"),
                () -> changeView(HistoryViewController.Mode.CARDS),
                view.mode() == HistoryViewController.Mode.CARDS
                        ? LumiButton.Kind.SELECTED
                        : LumiButton.Kind.NORMAL);
        addIconButton(right - 26, historyY + 4, "graph",
                Component.translatable("luma.history.view_graph"),
                () -> changeView(HistoryViewController.Mode.GRAPH),
                view.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiButton.Kind.SELECTED
                        : LumiButton.Kind.NORMAL);
        search = addTextField(
                panelX + 16, historyY + 4,
                Math.min(124, Math.max(74, panelWidth / 3 + 4)),
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
        latestCreated().ifPresent(version -> addIconButton(
                right - 26, panelY + 64, "folder",
                Component.translatable("luma.action.open_details"),
                () -> openDetails.accept(version), LumiButton.Kind.NORMAL));
        addRows(right);
    }

    private void addRows(int right) {
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
                    panelX + 16, rowsY(), panelWidth - 32);
            graphView.buttons(openDetails).forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            addIconButton(right - 26,
                    rowsY() + 6 + index * ROW_STRIDE,
                    "folder", Component.translatable("luma.action.open_details"),
                    () -> openDetails.accept(version),
                    LumiButton.Kind.NORMAL);
        }
    }

    @Override
    public void tick() {
        super.tick();
        long historyRevision = pages.revision(dimensionId);
        if (historyRevision != observedHistoryRevision) {
            observedHistoryRevision = historyRevision;
            loaded.clear();
            loadedOffset = -1;
            renderedPage = null;
            scroll = 0;
            request(0);
            rebuildWidgets();
            return;
        }
        if (queryDirty) {
            queryDirty = false;
            loaded.clear();
            loadedOffset = -1;
            scroll = 0;
            refocusSearch = search != null && search.isFocused();
            request(0);
            rebuildWidgets();
            return;
        }
        HistoryPagePayload latest = page().orElse(null);
        if (!Objects.equals(renderedPage, latest)) {
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
            renderPage(graphics, panelX, panelY, panelWidth, panelHeight);
            renderPageHeader(graphics, panelX, panelY, panelWidth, title,
                    Component.translatable("luma.dimensions.read_only"));
            renderLatest(graphics);
            renderPanel(graphics, panelX + 12, historyY(),
                    panelWidth - 24, historyHeight());
            renderTextField(graphics, search);
            if (graphView != null) graphView.renderConnections(graphics);
            renderRows(graphics);
            renderScrollbar(
                    graphics, panelX + 12, rowsY(), panelWidth - 26,
                    Math.max(0, panelY + panelHeight - 12 - rowsY()),
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

    private void renderRows(GuiGraphics graphics) {
        if (graphView != null) return;
        List<HistorySnapshotPayload.Version> visible = loaded.stream()
                .skip(scroll).limit(capacity()).toList();
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int y = rowsY() + index * ROW_STRIDE;
            LumiTheme.outlined(
                    graphics, panelX + 18, y, panelWidth - 36, ROW_HEIGHT,
                    LumiTheme.INSET,
                    scroll + index == 0
                            ? LumiTheme.ACCENT
                            : LumiTheme.INSET_BORDER);
            graphics.drawString(font,
                    font.plainSubstrByWidth(
                            VersionText.name(version), panelWidth - 100),
                    panelX + 26, y + 5, LumiTheme.TEXT, false);
            String tags = version.tags().isEmpty() ? ""
                    : " · #" + String.join(" #", version.tags().values());
            String metadata = version.author() + " · "
                    + TIME.format(Instant.ofEpochMilli(version.timestampMillis()))
                    + tags;
            graphics.drawString(font,
                    font.plainSubstrByWidth(metadata, panelWidth - 100),
                    panelX + 26, y + 17, LumiTheme.MUTED, false);
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
                            message.getString(), Math.max(1, panelWidth - 44)),
                    panelX + panelWidth / 2,
                    rowsY() + Math.max(0,
                            (panelY + panelHeight - rowsY() - 20) / 2),
                    error.isEmpty() ? LumiTheme.MUTED : LumiTheme.DANGER);
        }
    }

    private void renderLatest(GuiGraphics graphics) {
        latestCreated().ifPresent(version -> {
            renderPanel(graphics, panelX + 12, panelY + 46,
                    panelWidth - 24, LATEST_HEIGHT);
            graphics.drawString(font,
                    Component.translatable("luma.dashboard.latest_badge"),
                    panelX + 18, panelY + 52, LumiTheme.TEXT, false);
            commitCards.render(
                    graphics, version, version.tags(),
                    LumiCommitCard.layout(
                            panelX + 18, panelY + 66, panelWidth - 36,
                            ROW_HEIGHT, true, false, false),
                    LumiTheme.ACCENT, false, true);
        });
    }

    private Optional<HistorySnapshotPayload.Version> latestCreated() {
        return loaded.stream().filter(VersionText::featured)
                .max(java.util.Comparator.comparingLong(
                        HistorySnapshotPayload.Version::timestampMillis));
    }

    private int historyY() {
        return panelY + 46 + (latestCreated().isPresent()
                ? LATEST_HEIGHT + SECTION_GAP : 0);
    }

    private int rowsY() {
        return historyY() + 38;
    }

    private int historyHeight() {
        return Math.max(1, panelY + panelHeight - 12 - historyY());
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x < panelX || x >= panelX + panelWidth
                || y < rowsY() || y >= panelY + panelHeight - 12) {
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
            queryDirty = true;
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
        page().filter(current -> current.offset() != loadedOffset)
                .ifPresent(current -> {
                    if (current.offset() == 0) loaded.clear();
                    if (current.error().isEmpty()) loaded.addAll(current.versions());
                    loadedOffset = current.offset();
                    renderedPage = current;
                });
    }

    private int capacity() {
        return Math.max(0,
                (panelY + panelHeight - 12 - rowsY()) / ROW_STRIDE);
    }

    @Override public boolean isPauseScreen() { return false; }

    @FunctionalInterface
    public interface Requester {
        void request(String dimensionId, int offset, int limit, String query);
    }
}
