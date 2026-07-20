package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.client.state.ClientSurvivalSettingsStore;
import io.github.lumi.domain.model.WorkspaceSettings;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.telemetry.TelemetryService;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Active-workspace defaults and client-local diagnostic controls. */
public final class LumiSettingsScreen extends LumiPageScreen {
    private static final int SETTING_COUNT = 8;
    private static final int SETTING_STRIDE = LumiSettingRow.HEIGHT + 2;
    private final ClientHistoryStore history;
    private final TelemetryService telemetry;
    private final Consumer<WorkspaceSettings> updateWorkspace;
    private final ClientSurvivalSettingsStore survivalSettings;
    private final Runnable requestSurvivalSettings;
    private final Consumer<Boolean> updateSurvivalSettings;
    private boolean settingsLoaded;
    private boolean showZoneSaves;
    private boolean includeEntitiesOnRestore;
    private boolean previewGenerationEnabled;
    private boolean workspaceHudEnabled;
    private boolean automaticVersionsEnabled;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;
    private int contentOffset;
    private int scroll;
    private boolean survivalRequested;
    private long survivalRevision = -1;

    public LumiSettingsScreen(
            Screen parent,
            ClientHistoryStore history,
            TelemetryService telemetry,
            Consumer<WorkspaceSettings> updateWorkspace,
            ClientSurvivalSettingsStore survivalSettings,
            Runnable requestSurvivalSettings,
            Consumer<Boolean> updateSurvivalSettings) {
        super(parent, Component.translatable("luma.screen.settings.title", "Lumi"),
                ProjectTab.SETTINGS);
        this.history = Objects.requireNonNull(history, "history");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.updateWorkspace = Objects.requireNonNull(
                updateWorkspace, "updateWorkspace");
        this.survivalSettings = Objects.requireNonNull(
                survivalSettings, "survivalSettings");
        this.requestSurvivalSettings = Objects.requireNonNull(
                requestSurvivalSettings, "requestSurvivalSettings");
        this.updateSurvivalSettings = Objects.requireNonNull(
                updateSurvivalSettings, "updateSurvivalSettings");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiPageLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        contentX = page.bodyX();
        contentY = page.bodyY();
        contentWidth = page.bodyWidth();
        contentHeight = page.bodyHeight();
        loadWorkspaceSettings();
        requestSurvivalSettings();
        boolean hintVisible = supportsContextualHint(panelHeight)
                && addContextualHint(
                        ClientContextualHelpHint.SETTINGS,
                        contentX + 4, contentY + 4, contentWidth - 8);
        contentOffset = hintVisible ? contextualHintOffset(6) : 0;
        addSettings();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderPage(graphics, panelX, panelY, panelWidth, panelHeight);
            renderPageHeader(
                    graphics, panelX, panelY, panelWidth, title, null);
            renderPanel(
                    graphics, contentX, contentY, contentWidth, contentHeight);
            renderScrollbar(
                    graphics, contentX,
                    contentY + 4 + contentOffset,
                    contentWidth,
                    Math.max(0, contentHeight - 8 - contentOffset),
                    SETTING_COUNT, visibleSettingRows(), scroll,
                    value -> scroll = value);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void addSettings() {
        int x = contentX + 4;
        int width = contentWidth - 8;
        scroll = Math.min(scroll, maximumScroll());
        addToggleSetting(0, x, width,
                "luma.settings.show_hidden_commits",
                "luma.settings.show_hidden_commits_help",
                showZoneSaves, this::toggleZoneSaves);
        addToggleSetting(1, x, width,
                "luma.settings.restore_entities",
                "luma.settings.restore_entities_help",
                includeEntitiesOnRestore, this::toggleRestoreEntities);
        addToggleSetting(2, x, width,
                "luma.settings.preview_generation",
                "luma.settings.preview_generation_help",
                previewGenerationEnabled, this::togglePreviewGeneration);
        addToggleSetting(3, x, width,
                "luma.settings.workspace_hud",
                "luma.settings.workspace_hud_help",
                workspaceHudEnabled, this::toggleWorkspaceHud);
        var survival = survivalSettings.snapshot().orElse(
                new ClientSurvivalSettingsStore.Snapshot(false, false));
        addToggleSetting(4, x, width,
                "luma.settings.automatic_versions",
                "luma.settings.history_help",
                automaticVersionsEnabled, this::toggleAutomaticVersions);
        LumiSettingRow survivalRow = addToggleSetting(5, x, width,
                "luma.settings.survival_mode",
                "luma.settings.survival_mode_help",
                survival.enabled(), this::toggleSurvival);
        if (survivalRow != null) survivalRow.active = survival.configurable();
        boolean enabled = telemetry.settings().enabled();
        addToggleSetting(6, x, width,
                "luma.settings.telemetry_enabled",
                "luma.settings.telemetry_enabled_help",
                enabled, this::toggleTelemetry);
        addActionSetting(7, x, width,
                Component.translatable("luma.settings.telemetry_clear_queue"),
                Component.translatable("luma.settings.telemetry_pending",
                        telemetry.pendingEventCount()).append(" · ").append(
                        Component.translatable("luma.settings.telemetry_last_send",
                                telemetry.lastSendSummary())),
                () -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                });
    }

    private LumiSettingRow addToggleSetting(
            int index, int x, int width,
            String labelKey, String descriptionKey,
            boolean selected, Runnable action) {
        if (index < scroll || index >= scroll + visibleSettingRows()) return null;
        return addRenderableWidget(LumiSettingRow.toggle(
                x, settingY(index), width,
                Component.translatable(labelKey),
                Component.translatable(descriptionKey),
                selected, ignored -> action.run()));
    }

    private void addActionSetting(
            int index, int x, int width,
            Component label, Component description, Runnable action) {
        if (index < scroll || index >= scroll + visibleSettingRows()) return;
        addRenderableWidget(LumiSettingRow.action(
                x, settingY(index), width, label, description,
                ignored -> action.run()));
    }

    private int settingY(int index) {
        return contentY + 4 + contentOffset
                + (index - scroll) * SETTING_STRIDE;
    }

    static int visibleSettingRows(int panelHeight, int contentOffset) {
        return Math.min(SETTING_COUNT,
                Math.max(1,
                        (panelHeight - 58 - contentOffset + 2) / SETTING_STRIDE));
    }

    static boolean supportsContextualHint(int panelHeight) {
        return panelHeight >= 220;
    }

    private int visibleSettingRows() {
        return visibleSettingRows(panelHeight, contentOffset);
    }

    private int maximumScroll() {
        return Math.max(0, SETTING_COUNT - visibleSettingRows());
    }

    private void loadWorkspaceSettings() {
        if (settingsLoaded) return;
        WorkspaceSettings defaults = WorkspaceSettings.defaults();
        HistorySnapshotPayload.WorkspaceView active = history.state().snapshot().stream()
                .flatMap(snapshot -> snapshot.workspaces().stream())
                .filter(HistorySnapshotPayload.WorkspaceView::active)
                .findFirst().orElse(null);
        showZoneSaves = active == null
                ? !defaults.hideZoneCommits() : !active.hideZoneCommits();
        includeEntitiesOnRestore = active == null
                ? defaults.includeEntitiesOnRestore()
                : active.includeEntitiesOnRestore();
        previewGenerationEnabled = active == null
                ? defaults.previewGenerationEnabled()
                : active.previewGenerationEnabled();
        workspaceHudEnabled = active == null
                ? defaults.workspaceHudEnabled() : active.workspaceHudEnabled();
        automaticVersionsEnabled = active == null
                ? defaults.automaticVersionsEnabled()
                : active.automaticVersionsEnabled();
        settingsLoaded = true;
    }

    private void toggleZoneSaves() {
        showZoneSaves = !showZoneSaves;
        publishWorkspaceSettings();
    }

    private void toggleRestoreEntities() {
        includeEntitiesOnRestore = !includeEntitiesOnRestore;
        publishWorkspaceSettings();
    }

    private void publishWorkspaceSettings() {
        updateWorkspace.accept(new WorkspaceSettings(
                !showZoneSaves, includeEntitiesOnRestore,
                previewGenerationEnabled, workspaceHudEnabled,
                automaticVersionsEnabled));
        rebuildWidgets();
    }

    private void togglePreviewGeneration() {
        previewGenerationEnabled = !previewGenerationEnabled;
        publishWorkspaceSettings();
    }

    private void toggleWorkspaceHud() {
        workspaceHudEnabled = !workspaceHudEnabled;
        publishWorkspaceSettings();
    }

    private void toggleAutomaticVersions() {
        automaticVersionsEnabled = !automaticVersionsEnabled;
        publishWorkspaceSettings();
    }

    private void toggleTelemetry() {
        telemetry.setEnabled(!telemetry.settings().enabled());
        rebuildWidgets();
    }

    private void requestSurvivalSettings() {
        if (!survivalRequested) {
            survivalRequested = true;
            requestSurvivalSettings.run();
        }
    }

    private void toggleSurvival() {
        survivalSettings.snapshot()
                .filter(ClientSurvivalSettingsStore.Snapshot::configurable)
                .ifPresent(setting ->
                        updateSurvivalSettings.accept(!setting.enabled()));
    }

    @Override
    public void tick() {
        super.tick();
        long current = survivalSettings.revision();
        if (current != survivalRevision) {
            survivalRevision = current;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= contentX && x < contentX + contentWidth
                && y >= contentY && y < contentY + contentHeight) {
            int replacement = Math.max(0, Math.min(maximumScroll(),
                    scroll + (verticalAmount < 0 ? 1 : -1)));
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
