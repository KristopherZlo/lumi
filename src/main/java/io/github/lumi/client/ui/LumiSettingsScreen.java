package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientHistoryStore;
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
    private final ClientHistoryStore history;
    private final TelemetryService telemetry;
    private final Consumer<WorkspaceSettings> updateWorkspace;
    private boolean settingsLoaded;
    private boolean showZoneSaves;
    private boolean includeEntitiesOnRestore;
    private boolean previewGenerationEnabled;
    private boolean workspaceHudEnabled;
    private boolean narrow;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int cardsY;
    private int cardHeight;
    private int cardWidth;
    private int diagnosticsY;
    private int diagnosticsHeight;

    public LumiSettingsScreen(
            Screen parent,
            ClientHistoryStore history,
            TelemetryService telemetry,
            Consumer<WorkspaceSettings> updateWorkspace) {
        super(parent, Component.translatable("luma.screen.settings.title", "Lumi"),
                LegacyProjectTab.SETTINGS);
        this.history = Objects.requireNonNull(history, "history");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.updateWorkspace = Objects.requireNonNull(
                updateWorkspace, "updateWorkspace");
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
        narrow = panelWidth < 360 || panelHeight < 220;
        if (narrow) {
            addNarrowControls();
            return;
        }
        cardsY = panelY + 38;
        cardHeight = panelHeight < 300 ? 78 : 92;
        cardWidth = (panelWidth - 32) / 2;
        diagnosticsY = cardsY + cardHeight + 8;
        diagnosticsHeight = Math.max(
                68, panelY + panelHeight - 12 - diagnosticsY);
        int historyX = panelX + 12;
        int restoreX = historyX + cardWidth + 8;
        addLegacyButton(historyX + 8, cardsY + cardHeight - 26, cardWidth - 16,
                toggleLabel("luma.settings.show_hidden_commits", showZoneSaves),
                this::toggleZoneSaves, showZoneSaves
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(restoreX + 8, cardsY + cardHeight - 26, cardWidth - 16,
                toggleLabel(
                        "luma.settings.restore_entities", includeEntitiesOnRestore),
                this::toggleRestoreEntities, includeEntitiesOnRestore
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        boolean telemetryEnabled = telemetry.settings().enabled();
        int controlWidth = (panelWidth - 48) / 2;
        int displayY = diagnosticsY + (panelHeight < 300 ? 34 : 42);
        addLegacyButton(panelX + 20, displayY, controlWidth,
                toggleLabel("luma.settings.preview_generation", previewGenerationEnabled),
                this::togglePreviewGeneration, previewGenerationEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(panelX + 28 + controlWidth, displayY, controlWidth,
                toggleLabel("luma.settings.workspace_hud", workspaceHudEnabled),
                this::toggleWorkspaceHud, workspaceHudEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        int telemetryY = displayY + 24;
        addLegacyButton(panelX + 20, telemetryY, controlWidth,
                toggleLabel("luma.settings.telemetry_enabled", telemetryEnabled),
                this::toggleTelemetry, telemetryEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(panelX + 28 + controlWidth, telemetryY, controlWidth,
                Component.translatable("luma.settings.telemetry_clear_queue"),
                () -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.DANGER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
            graphics.drawString(font, title, panelX + 16, panelY + 18,
                    LegacyLumiTheme.TEXT, false);
            if (narrow) {
                renderNarrow(graphics);
            } else {
                renderCards(graphics);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void addNarrowControls() {
        int x = panelX + 16;
        int width = panelWidth - 32;
        addLegacyButton(x, panelY + 44, width,
                toggleLabel("luma.settings.show_hidden_commits", showZoneSaves),
                this::toggleZoneSaves, showZoneSaves
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(x, panelY + 68, width,
                toggleLabel(
                        "luma.settings.restore_entities", includeEntitiesOnRestore),
                this::toggleRestoreEntities, includeEntitiesOnRestore
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(x, panelY + 92, width,
                toggleLabel("luma.settings.preview_generation", previewGenerationEnabled),
                this::togglePreviewGeneration, previewGenerationEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(x, panelY + 116, width,
                toggleLabel("luma.settings.workspace_hud", workspaceHudEnabled),
                this::toggleWorkspaceHud, workspaceHudEnabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        boolean enabled = telemetry.settings().enabled();
        addLegacyButton(x, panelY + 140, width,
                toggleLabel("luma.settings.telemetry_enabled", enabled),
                this::toggleTelemetry, enabled
                        ? LumiLegacyButton.Kind.SELECTED : LumiLegacyButton.Kind.NORMAL);
        addLegacyButton(x, panelY + 164, width,
                Component.translatable("luma.settings.telemetry_clear_queue"),
                () -> {
                    telemetry.clearLocalQueue();
                    rebuildWidgets();
                }, LumiLegacyButton.Kind.DANGER);
    }

    private void renderNarrow(GuiGraphics graphics) {
        renderLegacyPanel(graphics, panelX + 12, panelY + 36,
                panelWidth - 24, Math.max(1, panelHeight - 46));
        if (panelHeight >= 216) {
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_pending",
                            telemetry.pendingEventCount()),
                    panelX + 16, panelY + 190, LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_last_send",
                            telemetry.lastSendSummary()),
                    panelX + 16, panelY + 202, LegacyLumiTheme.MUTED, false);
        }
    }

    private void renderCards(GuiGraphics graphics) {
        int historyX = panelX + 12;
        int restoreX = historyX + cardWidth + 8;
        renderLegacyPanel(graphics, historyX, cardsY, cardWidth, cardHeight);
        renderLegacyPanel(graphics, restoreX, cardsY, cardWidth, cardHeight);
        renderLegacyPanel(graphics, panelX + 12, diagnosticsY,
                panelWidth - 24, diagnosticsHeight);
        renderSection(graphics, historyX, cardsY, cardWidth, cardHeight - 28,
                "luma.settings.history_title", "luma.settings.show_hidden_commits_help");
        renderSection(graphics, restoreX, cardsY, cardWidth, cardHeight - 28,
                "luma.action.restore", "luma.settings.restore_entities_help");
        renderSection(graphics, panelX + 12, diagnosticsY, panelWidth - 24,
                panelHeight < 300 ? 32 : 48,
                "luma.settings.preview_title", "luma.settings.preview_help");
        int statusY = diagnosticsY + (panelHeight < 300 ? 84 : 92);
        if (statusY + 20 < diagnosticsY + diagnosticsHeight) {
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_pending",
                            telemetry.pendingEventCount()),
                    panelX + 20, statusY, LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable("luma.settings.telemetry_last_send",
                            telemetry.lastSendSummary()),
                    panelX + 20, statusY + 12, LegacyLumiTheme.MUTED, false);
        }
    }

    private void renderSection(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int textHeight,
            String titleKey,
            String helpKey) {
        graphics.drawString(font, Component.translatable(titleKey),
                x + 8, y + 8, LegacyLumiTheme.ACCENT, false);
        int lineY = y + 21;
        for (var line : font.split(
                Component.translatable(helpKey), Math.max(0, width - 16))) {
            if (lineY + 9 > y + textHeight) break;
            graphics.drawString(font, line, x + 8, lineY,
                    LegacyLumiTheme.MUTED, false);
            lineY += 10;
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
                previewGenerationEnabled, workspaceHudEnabled));
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

    private void toggleTelemetry() {
        telemetry.setEnabled(!telemetry.settings().enabled());
        rebuildWidgets();
    }

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ").append(
                Component.translatable(enabled ? "options.on" : "options.off"));
    }

    @Override public boolean isPauseScreen() { return false; }
}
