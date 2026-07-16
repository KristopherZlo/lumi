package io.github.lumi.client.ui;

import io.github.lumi.update.UpdateCheckResult;
import io.github.lumi.update.UpdateChecker;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Manual update check; all network work stays off the render thread. */
public final class LumiUpdateScreen extends Screen {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "lumi-update-check");
                thread.setDaemon(true);
                return thread;
            });
    private final Screen parent;
    private final UpdateChecker checker;
    private UpdateCheckResult result;
    private boolean checking;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiUpdateScreen(Screen parent, UpdateChecker checker) {
        super(Component.translatable("luma.more.updates_title"));
        this.parent = parent;
        this.checker = java.util.Objects.requireNonNull(checker, "checker");
    }

    @Override
    protected void init() {
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 292) / 2);
        if (result != null && result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            addRenderableWidget(Button.builder(
                    Component.translatable("luma.action.download_update"),
                    ignored -> Util.getPlatform().openUri(
                            result.release().orElseThrow().downloadUri()))
                    .bounds(panelX + 16, panelY + 214, panelWidth - 32, 20).build());
        }
        Button check = Button.builder(
                Component.translatable(checking
                        ? "luma.action.checking_updates" : "luma.action.check_updates"),
                ignored -> check())
                .bounds(panelX + 16, panelY + 242, panelWidth - 102, 20).build();
        check.active = !checking;
        addRenderableWidget(check);
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 76, panelY + 242, 60, 20).build());
    }

    private void check() {
        checking = true;
        result = null;
        rebuildWidgets();
        CompletableFuture.supplyAsync(checker::check, EXECUTOR)
                .exceptionally(failed -> UpdateCheckResult.failed())
                .thenAccept(outcome -> Minecraft.getInstance().execute(() -> {
                    if (minecraft.screen != this) {
                        return;
                    }
                    checking = false;
                    result = outcome;
                    rebuildWidgets();
                }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 292, 0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 18, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.more.updates_help"),
                panelX + 16, panelY + 42, 0xffaeb6c2, false);
        renderResult(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderResult(GuiGraphics graphics) {
        if (checking || result == null) {
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
        graphics.drawString(font, heading, panelX + 16, panelY + 76, 0xffffd166, false);
        int y = drawWrapped(graphics, body, panelY + 98, 0xffd8dee7);
        if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            graphics.drawString(font, Component.translatable("luma.update.changes_title"),
                    panelX + 16, y + 8, 0xffffffff, false);
            drawWrapped(graphics,
                    Component.literal(result.release().orElseThrow().summary()),
                    y + 26, 0xffaeb6c2);
        }
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int startY, int color) {
        int y = startY;
        for (var line : font.split(text, panelWidth - 32)) {
            if (y > panelY + 198) {
                break;
            }
            graphics.drawString(font, line, panelX + 16, y, color, false);
            y += 11;
        }
        return y;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
