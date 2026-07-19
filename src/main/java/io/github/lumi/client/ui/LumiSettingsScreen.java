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
public final class LumiSettingsScreen extends LumiLegacyPageScreen {
    private static final int SETTING_COUNT = 8;
    private static final int SETTING_STRIDE = 24;
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
                LegacyProjectTab.SETTINGS);
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
        beginLegacyInit();
        LegacyWorkspaceLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        loadWorkspaceSettings();
        requestSurvivalSettings();
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.SETTINGS,
                panelX + 12, panelY + 36, panelWidth - 24);
        contentOffset = hintVisible ? contextualHintOffset(8) : 0;
        addNarrowControls();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawString(font, title, panelX + 16, panelY + 18,
                    LegacyLumiTheme.TEXT, false);
            renderNarrow(graphics);
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void addNarrowControls() {
        int x = panelX + 16;
        int width = panelWidth - 32;
        scroll = Math.min(scroll, maximumScroll());
        addSetting(0, x, width,
                toggleLabel("luma.settings.show_hidden_commits", showZoneSaves),
                this::toggleZoneSaves, showZoneSaves
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addSetting(1, x, width,
                toggleLabel(
                        "luma.settings.restore_entities", includeEntitiesOnRestore),
                this::toggleRestoreEntities, includeEntitiesOnRestore
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addSetting(2, x, width,
                toggleLabel("luma.settings.preview_generation", previewGenerationEnabled),
                this::togglePreviewGeneration, previewGenerationEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addSetting(3, x, width,
                toggleLabel("luma.settings.workspace_hud", workspaceHudEnabled),
                this::toggleWorkspaceHud, workspaceHudEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        var survival = survivalSettings.snapshot().orElse(
                new ClientSurvivalSettingsStore.Snapshot(false, false));
        addSetting(4, x, width,
                toggleLabel(
                        "luma.settings.automatic_versions", automaticVersionsEnabled),
                this::toggleAutomaticVersions, automaticVersionsEnabled
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        LumiLegacyButton survivalButton = addSetting(5, x, width,
                toggleLabel("luma.settings.survival_mode", survival.enabled()),
                this::toggleSurvival, survival.enabled()
                        ? LumiLegacyButton.Kind.SELECTED
                        : LumiLegacyButton.Kind.NORMAL);
        if (survivalButton != null) survivalButton.active = survival.configurable();
        boolean enabled = telemetry.settings().enabled();
        addSetting(6, x, width,
                toggleLabel("luma.settings.telemetry_enabled", enabled),
                this::toggleTelemetry, enabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addSetting(7, x, width,
                Component.translatable("luma.settings.telemetry_clear_queue"),
                () -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.DANGER);
    }

    private LumiLegacyButton addSetting(
            int index, int x, int width, Component label,
            Runnable action, LumiLegacyButton.Kind kind) {
        if (index < scroll || index >= scroll + visibleSettingRows()) return null;
        return addLegacyContentButton(
                x, panelY + 44 + contentOffset + (index - scroll) * SETTING_STRIDE,
                width, label, action, kind);
    }

    static int visibleSettingRows(int panelHeight, int contentOffset) {
        return Math.min(SETTING_COUNT,
                Math.max(1, 1 + (panelHeight - 78 - contentOffset) / SETTING_STRIDE));
    }

    private int visibleSettingRows() {
        return visibleSettingRows(panelHeight, contentOffset);
    }

    private int maximumScroll() {
        return Math.max(0, SETTING_COUNT - visibleSettingRows());
    }

    private void renderNarrow(GuiGraphics graphics) {
        renderLegacyPanel(graphics, panelX + 12, panelY + 36,
                panelWidth - 24, Math.max(1, panelHeight - 46));
        if (panelHeight >= 264 + contentOffset) {
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_pending",
                            telemetry.pendingEventCount()),
                    panelX + 16, panelY + 238 + contentOffset,
                    LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_last_send",
                            telemetry.lastSendSummary()),
                    panelX + 16, panelY + 250 + contentOffset,
                    LegacyLumiTheme.MUTED, false);
        }
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

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ").append(
                Component.translatable(enabled ? "options.on" : "options.off"));
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= panelY + 36 && y < panelY + panelHeight) {
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
