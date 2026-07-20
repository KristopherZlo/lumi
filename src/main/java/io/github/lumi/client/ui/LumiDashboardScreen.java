package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.OnboardingTour;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Legacy project-window presentation backed by the immutable V2 history snapshot. */
public final class LumiDashboardScreen extends LumiLegacyModalScreen {
    static final int PANEL_PADDING = 6;
    static final int SECTION_GAP = 5;
    static final int CONTROL_GAP = 4;
    static final int CONTROL_HEIGHT = 18;
    static final int ICON_BUTTON_WIDTH = 26;
    static final int HISTORY_TOOLBAR_OFFSET = 7;
    static final int HISTORY_FIRST_ROW_OFFSET = 38;
    private static final int LATEST_TITLE_HEIGHT = 22;
    private static final int LATEST_BOTTOM_PADDING = 6;
    private static final int HISTORY_ROW_HEIGHT = 30;
    private static final int HISTORY_ROW_STRIDE = 34;
    private static final int COMPACT_HISTORY_ROW_HEIGHT = 54;
    private static final int COMPACT_HISTORY_ROW_STRIDE = 58;
    private static final int SUPPORT_PANEL_OFFSET = 103;
    private static final int SUPPORT_CREDIT_OFFSET = 23;
    private static final int SIDEBAR_BUTTON_STRIDE = 19;
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
    private final String modVersion;
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
    private int actionY;
    private int saveActionX;
    private int changesActionX;
    private int actionButtonWidth;
    private DashboardGeometry dashboardGeometry;
    private LumiCommitCard commitCards;
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
        modVersion = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(container -> container.getMetadata()
                        .getVersion().getFriendlyString())
                .orElse("?");
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
        commitCards = snapshot == null ? null
                : new LumiCommitCard(font, previews, snapshot.dimensionId());
        layout = LegacyWorkspaceLayout.fit(width, height);
        addSidebarButtons();
        addSupportButtons();
        DashboardGeometry baseGeometry = dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), 0);
        boolean hintVisible = addDashboardHint(baseGeometry.hintY());
        dashboardGeometry = dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(),
                hintVisible ? contextualHintOffset(0) : 0);
        if (hintVisible) {
            moveContextualHint(
                    layout.bodyX() + PANEL_PADDING, dashboardGeometry.hintY());
        }
        actionY = dashboardGeometry.actionY();
        int x = layout.bodyX() + PANEL_PADDING;
        int available = Math.max(0, layout.bodyWidth() - PANEL_PADDING * 2);
        int maximumTextWidth = Math.max(0,
                (available - ICON_BUTTON_WIDTH * 2 - CONTROL_GAP * 3) / 2);
        Component saveLabel = Component.translatable("luma.action.save_build");
        LumiLegacyButton saveButton = addLegacyContentButton(
                x, actionY, maximumTextWidth, saveLabel, openSave,
                LumiLegacyButton.Kind.PRIMARY);
        saveActionX = saveButton.getX();
        actionButtonWidth = saveButton.getWidth();
        x += saveButton.getWidth() + CONTROL_GAP;
        LumiLegacyButton amendButton = addLegacyContentButton(
                x, actionY, maximumTextWidth,
                Component.translatable("luma.action.amend_version"), openAmend,
                LumiLegacyButton.Kind.NORMAL);
        x += amendButton.getWidth() + CONTROL_GAP;
        changesActionX = x;
        addIconButton(x, actionY,
                "see-changes", "luma.action.see_changes", showChanges,
                LumiLegacyButton.Kind.NORMAL);
        addIconButton(x + ICON_BUTTON_WIDTH + CONTROL_GAP, actionY,
                "rollback", "key.lumi.quick_rollback", () -> {
            quickRollback.run();
            onClose();
        }, LumiLegacyButton.Kind.DANGER);

        historyY = dashboardGeometry.historyY();
        historyHeight = dashboardGeometry.historyHeight();
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
        if (dashboardGeometry.latestVisible()) {
            latestCreated().ifPresent(version -> addVersionActions(
                    version, latestCardY(dashboardGeometry)));
        }
        if (historyHeight < 28) {
            return;
        }
        int right = layout.bodyX() + layout.bodyWidth() - PANEL_PADDING;
        addIconButton(right - 56, historyY + HISTORY_TOOLBAR_OFFSET,
                "unordered-list",
                "luma.history.view_cards",
                () -> showHistoryMode(HistoryViewController.Mode.CARDS),
                historyView.mode() == HistoryViewController.Mode.CARDS
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addIconButton(right - 26, historyY + HISTORY_TOOLBAR_OFFSET, "graph",
                "luma.history.view_graph",
                () -> showHistoryMode(HistoryViewController.Mode.GRAPH),
                historyView.mode() == HistoryViewController.Mode.GRAPH
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        int searchWidth = Math.min(100, Math.max(70, layout.bodyWidth() / 4));
        int searchX = layout.bodyX() + PANEL_PADDING;
        search = addLegacyTextField(
                searchX, historyY + HISTORY_TOOLBAR_OFFSET,
                searchWidth, Component.translatable("luma.dashboard.search"));
        search.setMaxLength(HistoryPageRequestPayload.MAX_QUERY_LENGTH);
        search.setHint(Component.translatable("luma.dashboard.search"));
        search.setValue(searchQuery);
        search.setResponder(this::search);
        addBranchTabs(searchX + searchWidth + CONTROL_GAP, right - 60);
        if (refocusSearch) {
            setInitialFocus(search);
            search.setFocused(true);
            search.moveCursorToEnd(false);
            refocusSearch = false;
        }
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        int capacity = visibleHistoryRows(
                historyHeight, Integer.MAX_VALUE, layout.bodyWidth());
        historyScroll = Math.min(historyScroll,
                Math.max(0, versions.size() - capacity));
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(capacity).toList();
        if (historyView.mode() == HistoryViewController.Mode.GRAPH) {
            List<HistoryGraphLayout.Node> nodes = graphLayout
                    .build(visible, snapshot.branches());
            graphView = new LumiHistoryGraphView(
                    snapshot.dimensionId(), previews, nodes, snapshot.zones(),
                    layout.bodyX() + PANEL_PADDING,
                    historyY + HISTORY_FIRST_ROW_OFFSET,
                    layout.bodyWidth() - PANEL_PADDING * 2);
            graphView.buttons(openDetails).forEach(this::addRenderableWidget);
            return;
        }
        graphView = null;
        for (int index = 0; index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = historyY + HISTORY_FIRST_ROW_OFFSET
                    + index * historyRowStride(layout.bodyWidth());
            addVersionActions(version, rowY);
        }
    }

    private void addVersionActions(
            HistorySnapshotPayload.Version version, int rowY) {
        LumiCommitCard.Layout card = versionCardLayout(
                layout.bodyX(), layout.bodyWidth(), rowY);
        addIconButton(card.actionX(0), card.actionY(),
                "rollback", "luma.action.restore",
                () -> openRestore.accept(version), LumiLegacyButton.Kind.PRIMARY);
        addIconButton(card.actionX(1), card.actionY(),
                "folder", "luma.action.open_details",
                () -> openDetails.accept(version), LumiLegacyButton.Kind.NORMAL);
        addIconButton(card.actionX(2), card.actionY(),
                "branch", "luma.action.create_idea",
                () -> createBranch.accept(version), LumiLegacyButton.Kind.NORMAL);
        addIconButton(card.actionX(3), card.actionY(),
                "tags", "luma.action.edit_tags",
                () -> editTags(version), LumiLegacyButton.Kind.NORMAL);
    }

    private boolean addDashboardHint(int y) {
        int x = layout.bodyX() + PANEL_PADDING;
        int width = Math.max(1, layout.bodyWidth() - PANEL_PADDING * 2);
        if (addContextualHint(ClientContextualHelpHint.HISTORY, x, y, width)
                || addContextualHint(
                        ClientContextualHelpHint.SHORTCUTS, x, y, width)) {
            return true;
        }
        if (snapshot == null) {
            return false;
        }
        if (snapshot.pendingKeys() == 0) {
            return addContextualHint(
                    ClientContextualHelpHint.CLEAN_STATE, x, y, width);
        }
        if (addContextualHint(ClientContextualHelpHint.SAVE, x, y, width)) {
            return true;
        }
        return addContextualHint(
                ClientContextualHelpHint.QUICK_ROLLBACK, x, y, width);
    }

    private void addSidebarButtons() {
        if (compactSidebar()) {
            addCompactSidebarButtons();
            return;
        }
        int x = layout.windowX() + 12;
        int width = layout.sidebarWidth() - 24;
        int y = layout.windowY() + 102;
        Integer zoneColor = activeZoneColor().orElse(null);
        addButton(x, y, width, "luma.tab.history", this::showHistory,
                tabKind(LegacyProjectTab.HISTORY));
        addButton(x, y + SIDEBAR_BUTTON_STRIDE, width, "luma.tab.zones",
                () -> openTab(LegacyProjectTab.ZONES, openZones),
                tabKind(LegacyProjectTab.ZONES), zoneColor);
        addButton(x, y + SIDEBAR_BUTTON_STRIDE * 2, width, "luma.tab.variants",
                () -> openTab(LegacyProjectTab.VARIANTS, openBranches),
                tabKind(LegacyProjectTab.VARIANTS), zoneColor);
        addButton(x, y + SIDEBAR_BUTTON_STRIDE * 3, width,
                "luma.tab.compare", this::showCompare,
                tabKind(LegacyProjectTab.COMPARE));
        addButton(x, y + SIDEBAR_BUTTON_STRIDE * 4, width,
                "luma.tab.import_export",
                () -> openTab(LegacyProjectTab.IMPORT_EXPORT, openPackages),
                tabKind(LegacyProjectTab.IMPORT_EXPORT));
        addButton(x, y + SIDEBAR_BUTTON_STRIDE * 5, width,
                "luma.action.settings",
                () -> openTab(LegacyProjectTab.SETTINGS, openSettings),
                tabKind(LegacyProjectTab.SETTINGS));
        addButton(x, y + SIDEBAR_BUTTON_STRIDE * 6, width,
                "luma.action.more",
                () -> openTab(LegacyProjectTab.MORE, openMore),
                tabKind(LegacyProjectTab.MORE));
    }

    private void addCompactSidebarButtons() {
        Integer zoneColor = activeZoneColor().orElse(null);
        addCompactSidebarButton(0,
                "graph", "luma.tab.history", this::showHistory,
                tabKind(LegacyProjectTab.HISTORY));
        addCompactSidebarButton(1,
                "bookmarks", "luma.tab.zones",
                () -> openTab(LegacyProjectTab.ZONES, openZones),
                tabKind(LegacyProjectTab.ZONES), zoneColor);
        addCompactSidebarButton(2,
                "branch", "luma.tab.variants",
                () -> openTab(LegacyProjectTab.VARIANTS, openBranches),
                tabKind(LegacyProjectTab.VARIANTS), zoneColor);
        addCompactSidebarButton(3,
                "see-changes", "luma.tab.compare",
                this::showCompare, tabKind(LegacyProjectTab.COMPARE));
        addCompactSidebarButton(4,
                "folder", "luma.tab.import_export",
                () -> openTab(LegacyProjectTab.IMPORT_EXPORT, openPackages),
                tabKind(LegacyProjectTab.IMPORT_EXPORT));
        addCompactSidebarButton(5,
                "sliders", "luma.action.settings",
                () -> openTab(LegacyProjectTab.SETTINGS, openSettings),
                tabKind(LegacyProjectTab.SETTINGS));
        addCompactSidebarButton(6,
                "unordered-list", "luma.action.more",
                () -> openTab(LegacyProjectTab.MORE, openMore),
                tabKind(LegacyProjectTab.MORE));
    }

    private void addCompactSidebarButton(
            int index, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addCompactSidebarButton(index, icon, translation, action, kind, null);
    }

    private void addCompactSidebarButton(
            int index, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind, Integer accent) {
        addRenderableWidget(new LumiLegacyButton(
                compactSidebarActionX(layout, index),
                compactSidebarActionY(layout, index),
                compactSidebarActionWidth(layout), 20,
                Component.translatable(translation), ignored -> action.run(),
                kind, icon, accent));
    }

    static int compactSidebarActionX(LegacyWorkspaceLayout layout, int index) {
        return layout.windowX() + 11 + (tinySidebar(layout)
                ? index * 16 : index % 4 * 28);
    }

    static int compactSidebarActionY(LegacyWorkspaceLayout layout, int index) {
        return layout.windowY() + (tinySidebar(layout)
                ? 30 : 60 + index / 4 * 24);
    }

    static int compactSidebarActionWidth(LegacyWorkspaceLayout layout) {
        return tinySidebar(layout) ? 14 : ICON_BUTTON_WIDTH;
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

    OnboardingSpotlightLayout.Rect onboardingTarget(
            OnboardingTour.Kind kind) {
        return switch (kind) {
            case SPOTLIGHT_SAVE -> new OnboardingSpotlightLayout.Rect(
                    saveActionX, actionY, actionButtonWidth, CONTROL_HEIGHT);
            case SPOTLIGHT_CHANGES -> new OnboardingSpotlightLayout.Rect(
                    changesActionX, actionY, ICON_BUTTON_WIDTH, CONTROL_HEIGHT);
            case SPOTLIGHT_RESTORE -> new OnboardingSpotlightLayout.Rect(
                    historyActionX(layout.bodyX(), layout.bodyWidth(), 0),
                    historyActionY(
                            dashboardGeometry.latestVisible()
                                    ? latestCardY(dashboardGeometry)
                                    : historyY + HISTORY_FIRST_ROW_OFFSET,
                            layout.bodyWidth()),
                    ICON_BUTTON_WIDTH, CONTROL_HEIGHT);
            default -> throw new IllegalArgumentException(
                    "Page does not have a Dashboard spotlight");
        };
    }

    private void addSupportButtons() {
        int x = layout.windowX() + 16;
        int y = supportTop(layout) + 17;
        int width = layout.sidebarWidth() - 32;
        addSupportButton(x, y, width,
                "buymeacoffee", "luma.action.buy_me_a_coffee",
                () -> Util.getPlatform().openUri(COFFEE_URI),
                LumiLegacyButton.Kind.NORMAL);
        addSupportButton(x, y + 20, width,
                "paypal", "luma.action.paypal_donate",
                () -> Util.getPlatform().openUri(PAYPAL_URI),
                LumiLegacyButton.Kind.NORMAL);
        addSupportButton(x, y + 40, width,
                "bug", "luma.action.report_bug",
                () -> Util.getPlatform().openUri(BUG_URI),
                LumiLegacyButton.Kind.NORMAL);
    }

    private void addSupportButton(
            int x, int y, int width, String icon, String translation,
            Runnable action, LumiLegacyButton.Kind kind) {
        addRenderableWidget(new LumiLegacyButton(
                x, y, width, 20, Component.translatable(translation),
                ignored -> action.run(), kind, icon));
    }

    static int supportTop(LegacyWorkspaceLayout layout) {
        return layout.windowY() + layout.windowHeight() - SUPPORT_PANEL_OFFSET;
    }

    static int supportCreditY(LegacyWorkspaceLayout layout) {
        return layout.windowY() + layout.windowHeight() - SUPPORT_CREDIT_OFFSET;
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

    private void addBranchTabs(int x, int right) {
        for (HistorySnapshotPayload.Branch branch : snapshot.branches()) {
            if (right - x < 24) break;
            LumiLegacyButton tab = addLegacyContentButton(
                    x, historyY + HISTORY_TOOLBAR_OFFSET, right - x,
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
        rebuildWidgets();
    }

    private void editTags(HistorySnapshotPayload.Version version) {
        minecraft.setScreen(new LumiVersionTagsScreen(
                this, displayedTags(version), replacement -> {
                    updateTags.accept(version.id(), replacement);
                    optimisticTags.put(version.id(), replacement);
                }));
    }

    private VersionTags displayedTags(HistorySnapshotPayload.Version version) {
        return optimisticTags.getOrDefault(version.id(), version.tags());
    }

    private Optional<HistorySnapshotPayload.Version> latestCreated() {
        return snapshot.versions().stream().max(
                Comparator.comparingLong(
                        HistorySnapshotPayload.Version::timestampMillis));
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
        alignLegacyNavigation(x, y, layout.windowWidth());
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
        if (!tinySidebar(layout)) {
            graphics.drawString(font, Component.translatable("luma.window.mode"),
                    x + 14, y + 43, LegacyLumiTheme.MUTED, false);
        }
        int supportY = supportTop(layout);
        int creditY = supportCreditY(layout);
        LegacyLumiTheme.outlined(
                graphics, x + 10, supportY,
                layout.sidebarWidth() - 20, creditY - 4 - supportY,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.PANEL_BORDER);
        graphics.drawString(font, Component.translatable("luma.window.support"),
                x + 16, supportY + 7, LegacyLumiTheme.MUTED, false);
        int footerWidth = Math.max(1, layout.sidebarWidth() - 32);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.window.credit").getString(),
                        footerWidth),
                x + 16, creditY, LegacyLumiTheme.MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable(
                                "luma.window.mod_version", modVersion).getString(),
                        footerWidth),
                x + 16, creditY + 11, LegacyLumiTheme.MUTED, false);
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
        drawPanel(graphics, x, layout.bodyY(), width,
                dashboardGeometry.buildPanelHeight());
        if (dashboardGeometry.headerVisible()) {
            int titleOffset = dashboardGeometry.compact() ? 8 : 13;
            int pendingOffset = dashboardGeometry.compact() ? 23 : 31;
            graphics.drawString(font, Component.translatable("luma.project.build_title"),
                    x + PANEL_PADDING, layout.bodyY() + titleOffset,
                    LegacyLumiTheme.TEXT, false);
            int pending = snapshot.pendingKeys();
            Component pendingText = pendingStatistics.result(snapshot)
                    .filter(result -> result.error().isEmpty())
                    .<Component>map(result ->
                            PendingStatisticsText.summary(result.workspace()))
                    .orElseGet(() -> pending == 0
                            ? Component.translatable("luma.dashboard.pending_clean")
                            : Component.translatable(
                                    "luma.dashboard.workspace_pending", pending));
            graphics.drawString(font,
                    pendingText,
                    x + PANEL_PADDING, layout.bodyY() + pendingOffset,
                    pending == 0
                            ? LegacyLumiTheme.MUTED : LegacyLumiTheme.ACCENT,
                    false);
        }

        if (dashboardGeometry.latestVisible()) {
            drawPanel(graphics, x, dashboardGeometry.latestY(), width,
                    dashboardGeometry.latestHeight());
            graphics.drawString(font,
                    Component.translatable("luma.dashboard.latest_badge"),
                    x + PANEL_PADDING, dashboardGeometry.latestY() + 7,
                    LegacyLumiTheme.TEXT, false);
            latestCreated().ifPresent(version -> renderVersionCard(
                    graphics, version, latestCardY(dashboardGeometry), true));
        }
        if (historyHeight <= 0) {
            return;
        }
        drawPanel(graphics, x, historyY, width, historyHeight);
        if (historyHeight < 28) {
            return;
        }
        if (search != null) {
            renderLegacyTextField(graphics, search);
        }
        List<HistorySnapshotPayload.Version> versions = visibleVersions();
        if (graphView != null) {
            graphView.renderConnections(graphics);
        }
        int rows = visibleHistoryRows(
                historyHeight, versions.size(), layout.bodyWidth());
        List<HistorySnapshotPayload.Version> visible = versions.stream()
                .skip(historyScroll).limit(rows).toList();
        for (int index = 0; graphView == null && index < visible.size(); index++) {
            HistorySnapshotPayload.Version version = visible.get(index);
            int rowY = historyY + HISTORY_FIRST_ROW_OFFSET
                    + index * historyRowStride(width);
            renderVersionCard(graphics, version, rowY, false);
        }
        if (versions.isEmpty()) {
            graphics.drawString(font,
                    Component.translatable(searchQuery.isBlank()
                            ? "luma.simple.no_saved_help"
                            : "luma.project.history_search_help"),
                    x + PANEL_PADDING, historyY + 38,
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
                versionCardLayout(layout.bodyX(), layout.bodyWidth(), rowY),
                LegacyLumiTheme.ACCENT,
                snapshot.head().equals(version.id()), featured);
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
            int capacity = visibleHistoryRows(
                    historyHeight, Integer.MAX_VALUE, layout.bodyWidth());
            int maximum = Math.max(0, visibleVersions().size() - capacity);
            int replacement = Math.max(0, Math.min(
                    maximum, historyScroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != historyScroll) {
                historyScroll = replacement;
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

    static DashboardGeometry dashboardGeometry(
            int bodyY, int bodyHeight, int bodyWidth, int hintHeight) {
        boolean compact = bodyHeight < 220;
        int bodyBottom = bodyY + Math.max(0, bodyHeight);
        int preferredHintY = bodyY + (compact ? 42 : 64);
        int latestCandidateHeight = LATEST_TITLE_HEIGHT
                + historyRowHeight(bodyWidth) + LATEST_BOTTOM_PADDING;
        int latestHeight = latestCandidateHeight;
        int maximumActionY = bodyBottom - CONTROL_HEIGHT - PANEL_PADDING;
        int hintY = hintHeight > 0
                ? Math.max(bodyY, Math.min(
                        preferredHintY,
                        maximumActionY - hintHeight - SECTION_GAP))
                : preferredHintY;
        boolean headerVisible = hintHeight == 0
                || hintY >= bodyY + (compact ? 33 : 60);
        int actionY = hintHeight > 0
                ? hintY + hintHeight + SECTION_GAP
                : bodyY + (compact ? 38 : 46);
        int buildPanelHeight = actionY - bodyY
                + CONTROL_HEIGHT + PANEL_PADDING;
        int bandGap = compact ? 3 : SECTION_GAP;
        int latestY = Math.min(
                bodyBottom, bodyY + buildPanelHeight + bandGap);
        boolean latestFits = latestY + latestHeight + bandGap
                + HISTORY_FIRST_ROW_OFFSET + historyRowHeight(bodyWidth)
                <= bodyBottom;
        if (!latestFits) {
            latestHeight = 0;
        }
        int historyY = latestHeight == 0 ? latestY : Math.min(
                bodyBottom, latestY + latestHeight + bandGap);
        return new DashboardGeometry(
                hintY, actionY, buildPanelHeight,
                latestY, latestHeight, historyY,
                Math.max(0, bodyBottom - historyY), compact, headerVisible);
    }

    static int latestCardY(DashboardGeometry geometry) {
        return geometry.latestY() + LATEST_TITLE_HEIGHT;
    }

    static int visibleHistoryRows(
            int historyHeight, int availableVersions, int bodyWidth) {
        int firstRowSpace = historyHeight - HISTORY_FIRST_ROW_OFFSET;
        int rowHeight = historyRowHeight(bodyWidth);
        if (firstRowSpace < rowHeight) {
            return 0;
        }
        return Math.min(availableVersions,
                1 + (firstRowSpace - rowHeight) / historyRowStride(bodyWidth));
    }

    static boolean compactHistoryCards(int bodyWidth) {
        return bodyWidth < 240;
    }

    static int historyRowHeight(int bodyWidth) {
        return compactHistoryCards(bodyWidth)
                ? COMPACT_HISTORY_ROW_HEIGHT : HISTORY_ROW_HEIGHT;
    }

    static int historyRowStride(int bodyWidth) {
        return compactHistoryCards(bodyWidth)
                ? COMPACT_HISTORY_ROW_STRIDE : HISTORY_ROW_STRIDE;
    }

    static int historyTextWidth(int bodyWidth) {
        return versionCardLayout(0, bodyWidth, 0).textWidth();
    }

    static int historyActionX(
            int bodyX, int bodyWidth, int actionIndex) {
        return versionCardLayout(bodyX, bodyWidth, 0).actionX(actionIndex);
    }

    static int historyActionY(int rowY, int bodyWidth) {
        return versionCardLayout(0, bodyWidth, rowY).actionY();
    }

    static LumiCommitCard.Layout versionCardLayout(
            int bodyX, int bodyWidth, int rowY) {
        int cardX = bodyX + PANEL_PADDING * 2;
        return LumiCommitCard.layout(
                cardX, rowY, Math.max(0, bodyWidth - PANEL_PADDING * 4),
                historyRowHeight(bodyWidth), true, true,
                compactHistoryCards(bodyWidth));
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
        return layout.sidebarWidth() < 136 || layout.windowHeight() < 340;
    }

    private static boolean tinySidebar(LegacyWorkspaceLayout layout) {
        return layout.windowHeight() < 220;
    }

    record DashboardGeometry(
            int hintY,
            int actionY,
            int buildPanelHeight,
            int latestY,
            int latestHeight,
            int historyY,
            int historyHeight,
            boolean compact,
            boolean headerVisible) {
        boolean latestVisible() {
            return latestHeight > 0;
        }
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
