package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.client.state.ClientHistoryPageStore;
import io.github.lumi.client.state.ClientHistoryStore;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Legacy project-window presentation backed by the immutable V2 history snapshot. */
public final class LumiDashboardScreen extends LumiLegacyModalScreen {
    static final int HISTORY_TOP_OFFSET = 98;
    private static final int HISTORY_FIRST_ROW_OFFSET = 38;
    private static final int HISTORY_ROW_HEIGHT = 30;
    private static final int HISTORY_ROW_STRIDE = 34;
    private static final int PREVIEW_WIDTH = 40;
    private static final int PREVIEW_HEIGHT = 22;
    private static final int ICON_TEXTURE_SIZE = 24;
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private static final Identifier NO_PREVIEW_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/new-icons/image.png");
    private static final java.net.URI COFFEE_URI =
            java.net.URI.create("https://buymeacoffee.com/zl0yxp");
    private static final java.net.URI PAYPAL_URI = java.net.URI.create(
            "https://www.paypal.com/donate/?hosted_button_id=CY7A2U64JWY4W");
    private static final java.net.URI BUG_URI =
            java.net.URI.create("https://github.com/KristopherZlo/lumi/issues/new");
    private final Screen parent;
    private final ClientHistoryStore history;
    private final ClientHistoryPageStore historyPages;
    private final ZoneHistoryController.Requester requestPage;
    private final LumiComparePickerScreen.PageRequester requestComparePage;
    private final ClientPendingStatisticsStore pendingStatistics;
    private final Runnable requestPendingStatistics;
    private final ClientVersionPreviewStore previews;
    private final Runnable openSave;
    private final Runnable openAmend;
    private final Consumer<Screen> openBranches;
    private final Consumer<Screen> openZones;
    private final Consumer<Screen> openPackages;
    private final Consumer<Screen> openMore;
    private final Consumer<Screen> openSettings;
    private final Runnable showChanges;
    private final Runnable quickRollback;
    private final Consumer<HistorySnapshotPayload.Version> openDetails;
    private final Consumer<HistorySnapshotPayload.Version> openRestore;
    private final Consumer<HistorySnapshotPayload.Version> createBranch;
    private final BiConsumer<CommitId, VersionTags> updateTags;
    private final Consumer<VersionCompareController.Target> openCompare;
    private final HistoryViewController historyView = new HistoryViewController();
    private final HistoryGraphLayout graphLayout = new HistoryGraphLayout();
    private HistorySnapshotPayload snapshot;
    private LegacyWorkspaceLayout layout;
    private EditBox search;
    private String searchQuery = "";
    private boolean searchResultsDirty;
    private boolean refocusSearch;
    private LegacyProjectTab activeTab = LegacyProjectTab.HISTORY;
    private LumiHistoryGraphView graphView;
    private WorkspaceHistoryController pagedHistory;
    private HistoryPagePayload renderedPage;
    private PendingStatisticsPayload renderedStatistics;
    private int historyY;
    private int historyHeight;
    private int historyScroll;
    private CommitId editingTags;
    private EditBox tagEditor;
    private String tagError = "";
    private final Map<CommitId, VersionTags> optimisticTags = new HashMap<>();

    public LumiDashboardScreen(
            Screen parent,
            ClientHistoryStore history,
            ClientVersionPreviewStore previews,
            ClientHistoryPageStore historyPages,
            ZoneHistoryController.Requester requestPage,
            LumiComparePickerScreen.PageRequester requestComparePage,
            ClientPendingStatisticsStore pendingStatistics,
            Runnable requestPendingStatistics,
            Runnable openSave,
            Runnable openAmend,
            Consumer<Screen> openBranches,
            Consumer<Screen> openZones,
            Consumer<Screen> openPackages,
            Consumer<Screen> openMore,
            Consumer<Screen> openSettings,
            Runnable showChanges,
            Runnable quickRollback,
            Consumer<HistorySnapshotPayload.Version> openDetails,
            Consumer<HistorySnapshotPayload.Version> openRestore,
            Consumer<HistorySnapshotPayload.Version> createBranch,
            BiConsumer<CommitId, VersionTags> updateTags,
            Consumer<VersionCompareController.Target> openCompare) {
        super(parent, Component.translatable("luma.screen.dashboard.title"));
        this.parent = parent;
        this.history = Objects.requireNonNull(history, "history");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.historyPages = Objects.requireNonNull(historyPages, "historyPages");
        this.requestPage = Objects.requireNonNull(requestPage, "requestPage");
        this.requestComparePage = Objects.requireNonNull(
                requestComparePage, "requestComparePage");
        this.pendingStatistics = Objects.requireNonNull(
                pendingStatistics, "pendingStatistics");
        this.requestPendingStatistics = Objects.requireNonNull(
                requestPendingStatistics, "requestPendingStatistics");
        this.openSave = Objects.requireNonNull(openSave, "openSave");
        this.openAmend = Objects.requireNonNull(openAmend, "openAmend");
        this.openBranches = Objects.requireNonNull(openBranches, "openBranches");
        this.openZones = Objects.requireNonNull(openZones, "openZones");
        this.openPackages = Objects.requireNonNull(openPackages, "openPackages");
        this.openMore = Objects.requireNonNull(openMore, "openMore");
        this.openSettings = Objects.requireNonNull(openSettings, "openSettings");
        this.showChanges = Objects.requireNonNull(showChanges, "showChanges");
        this.quickRollback = Objects.requireNonNull(quickRollback, "quickRollback");
        this.openDetails = Objects.requireNonNull(openDetails, "openDetails");
        this.openRestore = Objects.requireNonNull(openRestore, "openRestore");
        this.createBranch = Objects.requireNonNull(createBranch, "createBranch");
        this.updateTags = Objects.requireNonNull(updateTags, "updateTags");
        this.openCompare = Objects.requireNonNull(openCompare, "openCompare");
    }

    @Override
    public void tick() {
        super.tick();
        HistorySnapshotPayload latest = history.state().snapshot().orElse(null);
        HistoryPagePayload latestPage = pagedHistory == null
                ? null : pagedHistory.page().orElse(null);
        PendingStatisticsPayload latestStatistics = latest == null
                ? null : pendingStatistics.result(latest).orElse(null);
        if (!Objects.equals(snapshot, latest)
                || !Objects.equals(renderedPage, latestPage)
                || !Objects.equals(renderedStatistics, latestStatistics)
                || searchResultsDirty) {
            refocusSearch = search != null && search.isFocused();
            searchResultsDirty = false;
            renderedPage = latestPage;
            renderedStatistics = latestStatistics;
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        beginLegacyInit();
        snapshot = history.state().snapshot().orElse(null);
        layout = LegacyWorkspaceLayout.fit(width, height);
        addSidebarButtons();
        addSupportButtons();
        int actionY = layout.bodyY() + 56;
        int x = layout.bodyX() + 14;
        int available = Math.max(0, layout.bodyWidth() - 28);
        int buttonWidth = Math.max(0, (available - 70) / 2);
        addButton(x, actionY, buttonWidth, "luma.action.save_build", openSave,
                LumiLegacyButton.Kind.PRIMARY);
        addButton(x + buttonWidth + 6, actionY, buttonWidth,
                "luma.action.amend_version", openAmend,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + buttonWidth * 2 + 12, actionY,
                "see-changes", "luma.action.see_changes", showChanges,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + buttonWidth * 2 + 44, actionY,
                "rollback", "key.lumi.quick_rollback", () -> {
            quickRollback.run();
            onClose();
        }, LumiLegacyButton.Kind.DANGER);

        historyY = layout.bodyY() + HISTORY_TOP_OFFSET;
        historyHeight = layout.bodyHeight() - HISTORY_TOP_OFFSET;
        addDashboardHint();
        if (snapshot == null) {
            return;
        }
        requestPendingStatistics();
        if (pagedHistory == null || !pagedHistory.matches(snapshot)) {
            pagedHistory = new WorkspaceHistoryController(
                    snapshot, historyPages, requestPage);
        }
        pagedHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS);
        pagedHistory.search(searchQuery);
        renderedPage = pagedHistory.page().orElse(null);
        renderedStatistics = pendingStatistics.result(snapshot).orElse(null);
        latestCreated().ifPresent(version -> addIconButton(
                layout.bodyX() + latestPanelWidth() - 28,
                historyY - 21, "folder", "luma.action.open_details",
                () -> openDetails.accept(version), LumiLegacyButton.Kind.NORMAL));
        addBranchTabs();
        int right = layout.bodyX() + layout.bodyWidth() - 14;
        addIconButton(right - 58, historyY + 7, "unordered-list",
                "luma.history.view_cards",
                () -> showHistoryMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addIconButton(right - 26, historyY + 7, "graph",
                "luma.history.view_graph",
                () -> showHistoryMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        int searchWidth = Math.min(100, Math.max(70, layout.bodyWidth() / 4));
        int searchX = layout.bodyX() + 14;
        search = new EditBox(
                font,
                searchX,
                historyY + 8,
                searchWidth,
                16,
                Component.translatable("luma.dashboard.search"));
        search.setBordered(false);
        search.setMaxLength(HistoryPageRequestPayload.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(searchQuery);
        search.setResponder(this::search);
        addRenderableWidget(search);
        if (refocusSearch) {
            setInitialFocus(search);
            search.setFocused(true);
            search.moveCursorToEnd(false);
            refocusSearch = false;
        }
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        int capacity = visibleHistoryRows(historyHeight, Integer.MAX_VALUE);
        historyScroll = Math.min(historyScroll,
                Math.max(0, versions.size() - capacity));
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(capacity).toList();
        if (historyView.mode() == HistoryViewController.Mode.GRAPH) {
            List<HistoryGraphLayout.Node> nodes = graphLayout
                    .build(visible, snapshot.branches());
            graphView = new LumiHistoryGraphView(
                    snapshot.dimensionId(), previews, nodes, snapshot.zones(),
                    layout.bodyX() + 10,
                    historyY + HISTORY_FIRST_ROW_OFFSET,
                    layout.bodyWidth() - 20);
            graphView.buttons(openDetails).forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = historyY + HISTORY_FIRST_ROW_OFFSET
                    + index * HISTORY_ROW_STRIDE;
            boolean tagEditing = version.id().equals(editingTags);
            if (tagEditing) {
                tagEditor = new EditBox(
                        font, layout.bodyX() + 64, rowY + 7,
                        Math.max(20, layout.bodyWidth() - 222), 16,
                        Component.translatable("luma.history.tags_input"));
                tagEditor.setMaxLength(VersionTags.MAX_SERIALIZED_LENGTH);
                tagEditor.setValue(displayedTags(version).serialize());
                tagEditor.setBordered(false);
                addRenderableWidget(tagEditor);
                setInitialFocus(tagEditor);
                tagEditor.setFocused(true);
            }
            addIconButton(right - 122, rowY + 6,
                    tagEditing ? "save" : "tags",
                    tagEditing ? "luma.action.save_tags" : "luma.action.edit_tags",
                    tagEditing ? this::saveTags : () -> editTags(version),
                    tagEditing ? LumiLegacyButton.Kind.PRIMARY
                            : LumiLegacyButton.Kind.NORMAL);
            addIconButton(right - 90, rowY + 6,
                    "rollback", "luma.action.restore",
                    () -> openRestore.accept(version), LumiLegacyButton.Kind.PRIMARY);
            addIconButton(right - 58, rowY + 6,
                    "folder", "luma.action.open_details",
                    () -> openDetails.accept(version), LumiLegacyButton.Kind.NORMAL);
            addIconButton(right - 26, rowY + 6,
                    "branch", "luma.action.create_idea",
                    () -> createBranch.accept(version), LumiLegacyButton.Kind.NORMAL);
        }
    }

    private void addDashboardHint() {
        int x = layout.bodyX() + 14;
        int y = layout.bodyY() + 50;
        int width = Math.max(1, layout.bodyWidth() - 28);
        if (addContextualHint(ClientContextualHelpHint.HISTORY, x, y, width)
                || addContextualHint(ClientContextualHelpHint.SHORTCUTS, x, y, width)
                || snapshot == null) {
            return;
        }
        if (snapshot.pendingKeys() == 0) {
            addContextualHint(ClientContextualHelpHint.CLEAN_STATE, x, y, width);
            return;
        }
        if (!addContextualHint(ClientContextualHelpHint.SAVE, x, y, width)) {
            addContextualHint(ClientContextualHelpHint.QUICK_ROLLBACK, x, y, width);
        }
    }

    private void addSidebarButtons() {
        if (compactSidebar()) {
            addCompactSidebarButtons();
            return;
        }
        int x = layout.windowX() + 12;
        int width = layout.sidebarWidth() - 24;
        int y = layout.windowY() + 108;
        Integer zoneColor = activeZoneColor().orElse(null);
        addButton(x, y, width, "luma.tab.history", this::showHistory,
                tabKind(LegacyProjectTab.HISTORY));
        addButton(x, y + 22, width, "luma.tab.zones",
                () -> openTab(LegacyProjectTab.ZONES, openZones),
                tabKind(LegacyProjectTab.ZONES), zoneColor);
        addButton(x, y + 44, width, "luma.tab.variants",
                () -> openTab(LegacyProjectTab.VARIANTS, openBranches),
                tabKind(LegacyProjectTab.VARIANTS), zoneColor);
        addButton(x, y + 66, width, "luma.tab.compare", this::showCompare,
                tabKind(LegacyProjectTab.COMPARE));
        addButton(x, y + 88, width, "luma.tab.import_export",
                () -> openTab(LegacyProjectTab.IMPORT_EXPORT, openPackages),
                tabKind(LegacyProjectTab.IMPORT_EXPORT));
        addButton(x, y + 110, width, "luma.action.settings",
                () -> openTab(LegacyProjectTab.SETTINGS, openSettings),
                tabKind(LegacyProjectTab.SETTINGS));
        addButton(x, y + 132, width, "luma.action.more",
                () -> openTab(LegacyProjectTab.MORE, openMore),
                tabKind(LegacyProjectTab.MORE));
    }

    private void addCompactSidebarButtons() {
        int x = layout.windowX() + 12;
        int y = layout.windowY() + 60;
        Integer zoneColor = activeZoneColor().orElse(null);
        addIconButton(x, y, "graph", "luma.tab.history", this::showHistory,
                tabKind(LegacyProjectTab.HISTORY));
        addIconButton(x + 32, y, "bookmarks", "luma.tab.zones",
                () -> openTab(LegacyProjectTab.ZONES, openZones),
                tabKind(LegacyProjectTab.ZONES), zoneColor);
        addIconButton(x, y + 26, "branch", "luma.tab.variants",
                () -> openTab(LegacyProjectTab.VARIANTS, openBranches),
                tabKind(LegacyProjectTab.VARIANTS), zoneColor);
        addIconButton(x + 32, y + 26, "see-changes", "luma.tab.compare",
                this::showCompare, tabKind(LegacyProjectTab.COMPARE));
        addIconButton(x, y + 52, "folder", "luma.tab.import_export",
                () -> openTab(LegacyProjectTab.IMPORT_EXPORT, openPackages),
                tabKind(LegacyProjectTab.IMPORT_EXPORT));
        addIconButton(x + 32, y + 52, "sliders", "luma.action.settings",
                () -> openTab(LegacyProjectTab.SETTINGS, openSettings),
                tabKind(LegacyProjectTab.SETTINGS));
        addIconButton(x, y + 78, "unordered-list", "luma.action.more",
                () -> openTab(LegacyProjectTab.MORE, openMore),
                tabKind(LegacyProjectTab.MORE));
    }

    void selectTab(LegacyProjectTab tab) {
        if (activeTab != tab) {
            activeTab = tab;
            rebuildWidgets();
        }
    }

    private void openTab(LegacyProjectTab tab, Consumer<Screen> destination) {
        selectTab(tab);
        destination.accept(this);
    }

    private void showHistory() {
        selectTab(LegacyProjectTab.HISTORY);
        if (minecraft.screen != this) {
            minecraft.setScreen(this);
        }
    }

    private void addSupportButtons() {
        int x = layout.windowX() + 16;
        int y = layout.windowY() + layout.windowHeight() - 36;
        addIconButton(x, y, "buymeacoffee", "luma.action.buy_me_a_coffee",
                () -> Util.getPlatform().openUri(COFFEE_URI),
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + 32, y, "paypal", "luma.action.paypal_donate",
                () -> Util.getPlatform().openUri(PAYPAL_URI),
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + 64, y, "bug", "luma.action.report_bug",
                () -> Util.getPlatform().openUri(BUG_URI),
                LumiLegacyButton.Kind.NORMAL);
    }

    public void openBranchHistory(String branch) {
        if (snapshot == null) return;
        if (pagedHistory == null || !pagedHistory.matches(snapshot)) {
            pagedHistory = new WorkspaceHistoryController(
                    snapshot, historyPages, requestPage);
        }
        pagedHistory.selectBranch(branch);
        historyScroll = 0;
        activeTab = LegacyProjectTab.HISTORY;
        minecraft.setScreen(this);
    }

    private void showCompare() {
        if (snapshot == null) {
            return;
        }
        selectTab(LegacyProjectTab.COMPARE);
        minecraft.setScreen(new LumiComparePickerScreen(
                this, snapshot, previews, historyPages, requestComparePage,
                openCompare));
    }

    private void search(String value) {
        if (searchQuery.equals(value)) {
            return;
        }
        searchQuery = value;
        if (pagedHistory != null) {
            pagedHistory.search(value);
        }
        searchResultsDirty = true;
    }

    private void addBranchTabs() {
        int x = layout.bodyX() + latestPanelWidth() + 6;
        int right = layout.bodyX() + layout.bodyWidth() - 10;
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (right - x < 24) break;
            LumiLegacyButton tab = addLegacyButton(
                    x, historyY - 17, right - x,
                    Component.literal(shortBranch(branch.name())),
                    () -> selectHistoryBranch(branch.name()),
                    pagedHistory.branch().value().equals(branch.name())
                            ? LumiLegacyButton.Kind.SELECTED
                            : LumiLegacyButton.Kind.NORMAL);
            x += tab.getWidth() + 4;
        }
    }

    private void selectHistoryBranch(String branch) {
        pagedHistory.selectBranch(branch);
        historyScroll = 0;
        editingTags = null;
        rebuildWidgets();
    }

    private void editTags(HistorySnapshotPayload.Version version) {
        editingTags = version.id();
        tagError = "";
        rebuildWidgets();
    }

    private void saveTags() {
        try {
            VersionTags replacement = VersionTags.parse(tagEditor.getValue());
            optimisticTags.put(editingTags, replacement);
            updateTags.accept(editingTags, replacement);
            editingTags = null;
            tagError = "";
            rebuildWidgets();
        } catch (IllegalArgumentException invalid) {
            tagError = invalid.getMessage() == null
                    ? "Invalid tags" : invalid.getMessage();
        }
    }

    private VersionTags displayedTags(HistorySnapshotPayload.Version version) {
        return optimisticTags.getOrDefault(version.id(), version.tags());
    }

    private Optional<HistorySnapshotPayload.Version> latestCreated() {
        return snapshot.versions().stream().max(
                Comparator.comparingLong(
                        HistorySnapshotPayload.Version::timestampMillis));
    }

    private int latestPanelWidth() {
        return Math.max(112, layout.bodyWidth() / 2 - 8);
    }

    private List<HistorySnapshotPayload.Version> visibleVersions() {
        return snapshot == null || pagedHistory == null
                ? List.of()
                : historyView.filtered(pagedHistory.versions(), searchQuery);
    }

    private void showHistoryMode(HistoryViewController.Mode mode) {
        historyView.show(mode);
        rebuildWidgets();
    }

    private static String shortBranch(String value) {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private LumiLegacyButton.Kind tabKind(LegacyProjectTab tab) {
        return activeTab == tab
                ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL;
    }

    private void addButton(
            int x, int y, int width, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addButton(x, y, width, translation, action, kind, null);
    }

    private void addButton(
            int x, int y, int width, String translation,
            Runnable action, LumiLegacyButton.Kind kind, Integer accent) {
        addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> action.run(), kind, null, accent));
    }

    private void addIconButton(
            int x, int y, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addLegacyIconButton(x, y, icon, Component.translatable(translation), action, kind);
    }

    private void addIconButton(
            int x, int y, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind, Integer accent) {
        addRenderableWidget(new LumiLegacyButton(
                x, y, 26, 20, Component.translatable(translation),
                ignored -> action.run(), kind, icon, accent));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        graphics.fill(0, 0, width, height, LegacyLumiTheme.BACKDROP);
        drawFrame(graphics);
        if (snapshot == null) {
            drawPanel(graphics, layout.bodyX(), layout.bodyY(),
                    layout.bodyWidth(), 96);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty_title"),
                    layout.bodyX() + 14, layout.bodyY() + 16,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, Component.translatable("luma.dashboard.empty"),
                    layout.bodyX() + 14, layout.bodyY() + 36,
                    LegacyLumiTheme.MUTED, false);
        } else {
            drawWorkspace(graphics);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        if (graphView != null) {
            graphView.renderHover(graphics, font, render.mouseX(), render.mouseY());
        }
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void drawFrame(GuiGraphics graphics) {
        int x = layout.windowX();
        int y = layout.windowY();
        int right = x + layout.windowWidth();
        int bottom = y + layout.windowHeight();
        graphics.fill(x, y, right, bottom,
                activeZoneColor().orElse(LegacyLumiTheme.WINDOW_BORDER));
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1,
                LegacyLumiTheme.WINDOW);
        graphics.fill(x + 1, y + 1, layout.contentX(), bottom - 1,
                LegacyLumiTheme.SIDEBAR);
        graphics.fill(layout.contentX(), y + 1, right - 1,
                y + layout.titleHeight(), LegacyLumiTheme.TITLEBAR);
        graphics.drawString(font, "Lumi", x + 14, y + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.window.mode"),
                x + 14, y + 43, LegacyLumiTheme.MUTED, false);
        int supportY = y + layout.windowHeight() - 58;
        LegacyLumiTheme.outlined(
                graphics, x + 10, supportY,
                layout.sidebarWidth() - 20, 48,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
        graphics.drawString(font, Component.translatable("luma.window.support"),
                x + 16, supportY + 7, LegacyLumiTheme.MUTED, false);
        if (snapshot != null) {
            if (!compactSidebar()) {
                drawChip(graphics, x + 14, y + 62,
                        shortDimension(snapshot.dimensionId()));
                drawChip(graphics, x + 14, y + 84, shortBranch());
            }
            graphics.drawString(font,
                    Component.translatable("luma.screen.project.title",
                            snapshot.workspaceName()),
                    layout.contentX() + 16, y + 15,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.window.home_help"),
                    layout.contentX() + 16, y + 32,
                    LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawWorkspace(GuiGraphics graphics) {
        int x = layout.bodyX();
        int width = layout.bodyWidth();
        drawPanel(graphics, x, layout.bodyY(), width, 90);
        graphics.drawString(font, Component.translatable("luma.project.build_title"),
                x + 14, layout.bodyY() + 13, LegacyLumiTheme.TEXT, false);
        int pending = snapshot.pendingKeys();
        Component pendingText = pending == 0
                ? Component.translatable("luma.dashboard.pending_clean")
                : Component.translatable("luma.dashboard.workspace_pending", pending);
        graphics.drawString(font,
                pendingText,
                x + 14, layout.bodyY() + 31,
                pending == 0
                        ? LegacyLumiTheme.MUTED : LegacyLumiTheme.ACCENT,
                false);
        pendingStatistics.result(snapshot)
                .filter(result -> result.error().isEmpty())
                .ifPresent(result -> graphics.drawString(
                        font,
                        PendingStatisticsText.summary(result.workspace()),
                        x + 14, layout.bodyY() + 50,
                        LegacyLumiTheme.ACCENT, false));

        latestCreated().ifPresent(version -> {
            int latestWidth = latestPanelWidth();
            LegacyLumiTheme.outlined(
                    graphics, x, historyY - 24, latestWidth, 22,
                    LegacyLumiTheme.STATUS, LegacyLumiTheme.STATUS_BORDER);
            String latest = Component.translatable(
                    "luma.ideas.latest_save", version.message(),
                    HISTORY_TIME.format(Instant.ofEpochMilli(
                            version.timestampMillis()))).getString();
            graphics.drawString(font,
                    font.plainSubstrByWidth(latest, latestWidth - 34),
                    x + 6, historyY - 17, LegacyLumiTheme.ACCENT, false);
        });
        drawPanel(graphics, x, historyY, width, historyHeight);
        int titleX = search == null ? x + 14
                : search.getX() + search.getWidth() + 8;
        graphics.drawString(font, Component.translatable("luma.project.history_title"),
                titleX, historyY + 13, LegacyLumiTheme.TEXT, false);
        if (search != null) {
            LegacyLumiTheme.outlined(
                    graphics,
                    search.getX() - 2,
                    search.getY() - 2,
                    search.getWidth() + 4,
                    search.getHeight() + 4,
                    LegacyLumiTheme.INSET,
                    LegacyLumiTheme.INSET_BORDER);
        }
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (graphView != null) {
            graphView.renderConnections(graphics);
        }
        int rows = visibleHistoryRows(historyHeight, versions.size());
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(rows).toList();
        for (int index = 0; graphView == null && index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = historyY + HISTORY_FIRST_ROW_OFFSET
                    + index * HISTORY_ROW_STRIDE;
            LegacyLumiTheme.outlined(
                    graphics, x + 10, rowY, width - 20, HISTORY_ROW_HEIGHT,
                    LegacyLumiTheme.INSET,
                    snapshot.head().equals(version.id())
                            ? LegacyLumiTheme.ACCENT : LegacyLumiTheme.INSET_BORDER);
            drawPreview(graphics, version, x + 16, rowY + 4);
            if (!version.id().equals(editingTags)) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                version.message(), Math.max(0, width - 244)),
                        x + 64, rowY + 5, LegacyLumiTheme.TEXT, false);
                String tags = displayedTags(version).isEmpty() ? ""
                        : " · #" + String.join(" #", displayedTags(version).values());
                String active = snapshot.head().equals(version.id())
                        ? " · " + Component.translatable(
                                "luma.project.active_head_badge").getString() : "";
                String meta = version.author() + " · "
                        + HISTORY_TIME.format(Instant.ofEpochMilli(
                                version.timestampMillis()))
                        + " · " + version.statistics().blocks() + " blocks"
                        + tags + active;
                graphics.drawString(font,
                        font.plainSubstrByWidth(meta, Math.max(0, width - 244)),
                        x + 64, rowY + 17, LegacyLumiTheme.MUTED, false);
            } else if (!tagError.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(tagError, Math.max(0, width - 244)),
                        x + 64, rowY + 20, LegacyLumiTheme.DANGER, false);
            }
        }
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable(searchQuery.isBlank()
                            ? "luma.simple.no_saved_help"
                            : "luma.project.history_search_help"),
                    x + 14, historyY + 38, LegacyLumiTheme.MUTED, false);
        }
    }

    private void drawPreview(
            GuiGraphics graphics, HistorySnapshotPayload.Version version, int x, int y) {
        var texture = previews.texture(snapshot.dimensionId(), version.id()).orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    texture.width(), texture.height(), texture.width(), texture.height());
            return;
        }
        LegacyLumiTheme.outlined(graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                LegacyLumiTheme.WINDOW, LegacyLumiTheme.INSET_BORDER);
        int iconSize = 12;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, NO_PREVIEW_ICON,
                x + (PREVIEW_WIDTH - iconSize) / 2,
                y + (PREVIEW_HEIGHT - iconSize) / 2,
                0, 0, iconSize, iconSize,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (layout != null && snapshot != null
                && x >= layout.bodyX()
                && x < layout.bodyX() + layout.bodyWidth()
                && y >= historyY + HISTORY_FIRST_ROW_OFFSET
                && y < historyY + historyHeight) {
            int capacity = visibleHistoryRows(historyHeight, Integer.MAX_VALUE);
            int maximum = Math.max(0, visibleVersions().size() - capacity);
            int replacement = Math.max(0, Math.min(
                    maximum, historyScroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != historyScroll) {
                historyScroll = replacement;
                editingTags = null;
                rebuildWidgets();
            } else if (verticalAmount < 0 && pagedHistory.hasNext()) {
                pagedHistory.next();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        LegacyLumiTheme.outlined(graphics, x, y, width, height,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
    }

    private void requestPendingStatistics() {
        if (snapshot.pendingKeys() > 0
                && pendingStatistics.result(snapshot).isEmpty()
                && !pendingStatistics.pending(snapshot)) {
            requestPendingStatistics.run();
        }
    }

    static int visibleHistoryRows(int historyHeight, int availableVersions) {
        int firstRowSpace = historyHeight - HISTORY_FIRST_ROW_OFFSET;
        if (firstRowSpace < HISTORY_ROW_HEIGHT) {
            return 0;
        }
        return Math.min(availableVersions,
                1 + (firstRowSpace - HISTORY_ROW_HEIGHT) / HISTORY_ROW_STRIDE);
    }

    private void drawChip(GuiGraphics graphics, int x, int y, String text) {
        int width = Math.min(layout.sidebarWidth() - 28, font.width(text) + 12);
        LegacyLumiTheme.outlined(graphics, x, y, width, 17,
                LegacyLumiTheme.CHIP, LegacyLumiTheme.CHIP_BORDER);
        graphics.drawString(font, text, x + 6, y + 5,
                LegacyLumiTheme.MUTED, false);
    }

    private String shortBranch() {
        String value = snapshot.branchName();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String shortDimension(String value) {
        return switch (value) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }

    private Optional<Integer> activeZoneColor() {
        return snapshot == null ? Optional.empty() : snapshot.zones().stream()
                .filter(HistorySnapshotPayload.ZoneView::active)
                .map(HistorySnapshotPayload.ZoneView::color)
                .findFirst();
    }

    private boolean compactSidebar() {
        return layout.sidebarWidth() < 136 || layout.windowHeight() < 320;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
