package io.github.lumi.client.ui;

import io.github.lumi.update.ClientUpdatePreferenceRepository;
import io.github.lumi.update.UpdateCheckResult;
import io.github.lumi.update.UpdateChecker;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Manual update check; all network work stays off the render thread. */
public final class LumiUpdateScreen extends LumiLegacyModalScreen {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "lumi-update-check");
                thread.setDaemon(true);
                return thread;
            });
    private final Screen parent;
    private final UpdateChecker checker;
    private final ClientUpdatePreferenceRepository preferences;
    private UpdateCheckResult result;
    private boolean checking;
    private boolean started;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int resultBottom;

    public LumiUpdateScreen(
            Screen parent,
            UpdateChecker checker,
            ClientUpdatePreferenceRepository preferences) {
        super(Component.translatable("luma.more.updates_title"));
        this.parent = parent;
        this.checker = java.util.Objects.requireNonNull(checker, "checker");
        this.preferences = java.util.Objects.requireNonNull(
                preferences, "preferences");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        boolean expanded = result != null
                && result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE;
        LegacyModalLayout layout = fitPanel(width, height, expanded);
        panelWidth = layout.width();
        panelHeight = layout.height();
        panelX = layout.x();
        panelY = layout.y();
        if (!started) {
            startCheck();
        }
        int bottomY = panelY + bottomActionOffset(panelHeight);
        if (result != null && result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            int buttonWidth = (panelWidth - 40) / 2;
            int firstRow = panelY + firstActionOffset(panelHeight);
            addLegacyButton(panelX + 16, firstRow, buttonWidth,
                    Component.translatable("luma.action.download_update"),
                    this::openDownload,
                    LumiLegacyButton.Kind.PRIMARY);
            addLegacyButton(panelX + 24 + buttonWidth, firstRow, buttonWidth,
                    Component.translatable("luma.action.open_changelog"),
                    this::openChangelog, LumiLegacyButton.Kind.NORMAL);
            addLegacyButton(panelX + 16, bottomY, buttonWidth,
                    Component.translatable("luma.action.later"),
                    this::later, LumiLegacyButton.Kind.NORMAL);
            addLegacyButton(panelX + 24 + buttonWidth, bottomY, buttonWidth,
                    Component.translatable("luma.action.dont_show_version"),
                    this::dismissVersion, LumiLegacyButton.Kind.DANGER);
            resultBottom = panelY + updateResultBottomOffset(panelHeight);
            return;
        }
        resultBottom = panelY + panelHeight - 16;
    }

    private void startCheck() {
        started = true;
        checking = true;
        result = null;
        CompletableFuture.supplyAsync(checker::check, EXECUTOR)
                .exceptionally(failed -> UpdateCheckResult.failed())
                .thenAccept(outcome -> Minecraft.getInstance().execute(() -> {
                    if (minecraft.screen != this) {
                        return;
                    }
                    checking = false;
                    result = outcome.release()
                            .filter(release -> preferences.ignored(release.version()))
                            .map(ignored -> UpdateCheckResult.upToDate())
                            .orElse(outcome);
                    rebuildWidgets();
                }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.more.updates_help"),
                panelX + 16, panelY + 42, LegacyLumiTheme.MUTED, false);
        renderLegacyPanel(graphics, panelX + 12, panelY + 66,
                panelWidth - 24, Math.max(1, resultBottom - panelY - 66));
        renderResult(graphics);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderResult(GuiGraphics graphics) {
        if (checking) {
            graphics.drawString(font,
                    Component.translatable("luma.action.checking_updates"),
                    panelX + 22, panelY + 76, LegacyLumiTheme.ACCENT, false);
            return;
        }
        if (result == null) {
            return;
        }
        Component heading;
        Component body;
        if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            var release = result.release().orElseThrow();
            heading = Component.translatable("luma.update.card_title", release.version());
            body = Component.translatable(
                    "luma.update.card_body", release.minecraftVersion());
        } else if (result.status() == UpdateCheckResult.Status.UP_TO_DATE) {
            heading = Component.translatable("luma.update.up_to_date_title");
            body = Component.translatable("luma.update.up_to_date_body");
        } else {
            heading = Component.translatable("luma.update.check_failed_title");
            body = Component.translatable("luma.update.check_failed_body");
        }
        graphics.drawString(font, heading, panelX + 22, panelY + 76,
                LegacyLumiTheme.ACCENT, false);
        int y = drawWrapped(graphics, body, panelY + 98, LegacyLumiTheme.TEXT);
        if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE
                && fitsResultLine(y + 8, resultBottom)) {
            graphics.drawString(font, Component.translatable("luma.update.changes_title"),
                    panelX + 22, y + 8, LegacyLumiTheme.TEXT, false);
            drawWrapped(graphics,
                    Component.literal(result.release().orElseThrow().summary()),
                    y + 26, LegacyLumiTheme.MUTED);
        }
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int startY, int color) {
        int y = startY;
        for (var line : font.split(text, panelWidth - 44)) {
            if (!fitsResultLine(y, resultBottom)) {
                break;
            }
            graphics.drawString(font, line, panelX + 22, y, color, false);
            y += 11;
        }
        return y;
    }

    static LegacyModalLayout fitPanel(int screenWidth, int screenHeight) {
        return fitPanel(screenWidth, screenHeight, false);
    }

    static LegacyModalLayout fitPanel(
            int screenWidth, int screenHeight, boolean expanded) {
        int width = Math.min(360, Math.max(1, screenWidth - 24));
        int height = Math.min(expanded ? 210 : 144,
                Math.max(1, screenHeight - 24));
        return new LegacyModalLayout(
                Math.max(0, (screenWidth - width) / 2),
                Math.max(0, (screenHeight - height) / 2), width, height);
    }

    static int bottomActionOffset(int panelHeight) {
        return panelHeight - 28;
    }

    static int firstActionOffset(int panelHeight) {
        return bottomActionOffset(panelHeight) - 26;
    }

    static int updateResultBottomOffset(int panelHeight) {
        return firstActionOffset(panelHeight) - 8;
    }

    static boolean fitsResultLine(int y, int resultBottom) {
        return y <= resultBottom - 12;
    }

    private void openDownload() {
        openUri(result.release().orElseThrow().downloadUri());
    }

    private void openChangelog() {
        openUri(result.release().orElseThrow().changelogUri());
    }

    private void openUri(java.net.URI uri) {
        Util.getPlatform().openUri(uri);
        later();
    }

    private void later() {
        minecraft.setScreen(parent);
    }

    private void dismissVersion() {
        preferences.dismiss(result.release().orElseThrow().version());
        minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
