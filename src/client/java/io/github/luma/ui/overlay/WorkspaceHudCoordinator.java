package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.WorkspaceHudSnapshot;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.controller.WorkspaceHudController;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Coordinates persistent HUD and action bar feedback for the active workspace.
 */
public final class WorkspaceHudCoordinator {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "workspace_hud");
    private static final int ACTIVE_REFRESH_INTERVAL_TICKS = 10;
    private static final int IDLE_REFRESH_INTERVAL_TICKS = 40;
    private static final int TERMINAL_DISPLAY_TICKS = 40;
    private static final WorkspaceHudCoordinator INSTANCE = new WorkspaceHudCoordinator();

    private final WorkspaceHudController controller = new WorkspaceHudController();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lumi-hud-worker");
        thread.setDaemon(true);
        return thread;
    });
    private WorkspaceHudSnapshot workspaceSnapshot;
    private CompletableFuture<WorkspaceHudSnapshot> pendingWorkspaceRefresh;
    private OperationSnapshot activeOperation;
    private OperationSnapshot retainedTerminalOperation;
    private String retainedTerminalFingerprint = "";
    private String lastLoggedActionbarFingerprint = "";
    private int retainedTerminalTicks = 0;
    private int refreshCooldown = 0;
    private boolean actionbarOwned = false;

    private WorkspaceHudCoordinator() {
    }

    public static WorkspaceHudCoordinator getInstance() {
        return INSTANCE;
    }

    public void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.OVERLAY_MESSAGE,
                HUD_ELEMENT_ID,
                this::render
        );
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null || !client.hasSingleplayerServer()) {
            this.clear();
            return;
        }

        boolean refreshed = this.completePendingRefresh();
        if (!refreshed) {
            this.refreshCooldown -= 1;
        }
        if (!refreshed
                && this.pendingWorkspaceRefresh == null
                && (this.refreshCooldown <= 0 || this.workspaceSnapshot == null)) {
            this.startWorkspaceRefresh();
        }

        OperationSnapshot current = this.workspaceSnapshot == null ? null : this.workspaceSnapshot.operationSnapshot();
        if (current != null && !current.terminal()) {
            this.activeOperation = current;
            if (LumaDebugLog.enabled(current)) {
                String actionbarFingerprint = this.actionbarFingerprint(current);
                if (!actionbarFingerprint.equals(this.lastLoggedActionbarFingerprint)) {
                    this.lastLoggedActionbarFingerprint = actionbarFingerprint;
                    LumaDebugLog.log(
                            current.handle(),
                            "hud",
                            "HUD actionbar updated for {} at stage={} summary={}",
                            current.handle().label(),
                            current.stage(),
                            OperationProgressPresenter.progressSummary(current)
                    );
                }
            }
            this.updateActionbar(client, current);
            return;
        }

        this.activeOperation = null;
        if (current != null && current.terminal()) {
            String fingerprint = this.fingerprint(current);
            if (!fingerprint.equals(this.retainedTerminalFingerprint)) {
                this.retainedTerminalFingerprint = fingerprint;
                this.retainedTerminalOperation = current;
                this.retainedTerminalTicks = TERMINAL_DISPLAY_TICKS;
                if (LumaDebugLog.enabled(current)) {
                    LumaDebugLog.log(
                            current.handle(),
                            "hud",
                            "Retaining terminal operation {} stage={} for {} ticks",
                            current.handle().label(),
                            current.stage(),
                            TERMINAL_DISPLAY_TICKS
                    );
                }
            }
        }

        if (this.retainedTerminalOperation != null && this.retainedTerminalTicks > 0) {
            this.retainedTerminalTicks -= 1;
            this.updateActionbar(client, this.retainedTerminalOperation);
            return;
        }

        if (this.actionbarOwned) {
            client.gui.setOverlayMessage(Component.empty(), false);
            this.actionbarOwned = false;
        }
    }

    private void clear() {
        this.workspaceSnapshot = null;
        if (this.pendingWorkspaceRefresh != null) {
            this.pendingWorkspaceRefresh.cancel(false);
            this.pendingWorkspaceRefresh = null;
        }
        this.activeOperation = null;
        this.retainedTerminalOperation = null;
        this.retainedTerminalFingerprint = "";
        this.lastLoggedActionbarFingerprint = "";
        this.retainedTerminalTicks = 0;
        this.refreshCooldown = 0;
        this.actionbarOwned = false;
    }

    private boolean hasActiveOperation(WorkspaceHudSnapshot snapshot) {
        return snapshot != null
                && snapshot.operationSnapshot() != null
                && !snapshot.operationSnapshot().terminal();
    }

    private void startWorkspaceRefresh() {
        this.pendingWorkspaceRefresh = CompletableFuture.supplyAsync(
                this.controller::loadCurrentWorkspaceSnapshot,
                this.refreshExecutor
        );
        this.refreshCooldown = 1;
    }

    private boolean completePendingRefresh() {
        if (this.pendingWorkspaceRefresh == null || !this.pendingWorkspaceRefresh.isDone()) {
            return false;
        }

        CompletableFuture<WorkspaceHudSnapshot> completed = this.pendingWorkspaceRefresh;
        this.pendingWorkspaceRefresh = null;
        try {
            this.workspaceSnapshot = completed.join();
            this.refreshCooldown = this.hasActiveOperation(this.workspaceSnapshot)
                    ? ACTIVE_REFRESH_INTERVAL_TICKS
                    : IDLE_REFRESH_INTERVAL_TICKS;
            this.logSnapshotRefresh();
        } catch (CompletionException exception) {
            LumaMod.LOGGER.debug("Failed to refresh Lumi workspace HUD snapshot", exception.getCause() == null
                    ? exception
                    : exception.getCause());
            this.refreshCooldown = IDLE_REFRESH_INTERVAL_TICKS;
        }
        return true;
    }

    private void logSnapshotRefresh() {
        if (this.workspaceSnapshot != null && this.workspaceSnapshot.debugEnabled()) {
            LumaDebugLog.log(
                    "hud",
                    "Refreshed HUD snapshot for {} branch={} pending(+{}, -{}) operation={}",
                    this.workspaceSnapshot.projectName(),
                    this.workspaceSnapshot.activeVariantId(),
                    this.workspaceSnapshot.plusCount(),
                    this.workspaceSnapshot.minusCount(),
                    this.workspaceSnapshot.operationSnapshot() == null
                            ? "none"
                            : this.workspaceSnapshot.operationSnapshot().stage()
            );
        }
    }

    private void updateActionbar(Minecraft client, OperationSnapshot snapshot) {
        client.gui.setOverlayMessage(ActionBarMessagePresenter.operation(snapshot), false);
        this.actionbarOwned = true;
    }

    private void render(GuiGraphics drawContext, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || this.workspaceSnapshot == null || !this.workspaceSnapshot.workspaceHudEnabled()) {
            return;
        }

        String titleText = "Lumi";
        String placeText = client.level == null ? "Overworld" : this.dimensionLabel(client.level.dimension().identifier().toString());
        String branchText = this.workspaceSnapshot.activeVariantId() == null || this.workspaceSnapshot.activeVariantId().isBlank()
                ? ""
                : "branch: " + this.workspaceSnapshot.activeVariantId();
        String counterLabel = "Unsaved:";
        String plusText = "+" + this.workspaceSnapshot.plusCount();
        String minusText = "-" + this.workspaceSnapshot.minusCount();

        int titleWidth = client.font.width(titleText);
        int placeWidth = client.font.width(placeText);
        int branchWidth = branchText.isBlank() ? 0 : client.font.width(branchText);
        int counterLabelWidth = client.font.width(counterLabel);
        int plusWidth = client.font.width(plusText);
        int minusWidth = client.font.width(minusText);
        int countersWidth = counterLabelWidth + 4 + plusWidth + 8 + minusWidth;
        int boxHeight = branchText.isBlank() ? 34 : 46;
        int boxWidth = Math.max(Math.max(titleWidth, placeWidth), Math.max(branchWidth, countersWidth)) + 20;
        int x = drawContext.guiWidth() - boxWidth - 8;
        int y = 8;

        RoundedHudRenderer.card(drawContext, x, y, boxWidth, boxHeight);
        drawContext.drawString(client.font, titleText, x + 10, y + 5, RoundedHudRenderer.TEXT, false);
        drawContext.drawString(client.font, placeText, x + 10, y + 15, 0xFFA8B2BE, false);
        int counterY = y + 25;
        if (!branchText.isBlank()) {
            drawContext.drawString(client.font, branchText, x + 10, y + 25, 0xFFA8B2BE, false);
            counterY = y + 36;
        }
        int counterX = x + 10;
        drawContext.drawString(client.font, counterLabel, counterX, counterY, 0xFFA8B2BE, false);
        int plusX = counterX + counterLabelWidth + 4;
        drawContext.drawString(client.font, plusText, plusX, counterY, 0xFF69E38A, false);
        drawContext.drawString(client.font, minusText, plusX + plusWidth + 8, counterY, 0xFFFF7373, false);
    }

    private String fingerprint(OperationSnapshot snapshot) {
        return snapshot.handle().id() + ":" + snapshot.updatedAt();
    }

    private String actionbarFingerprint(OperationSnapshot snapshot) {
        return snapshot.handle().id()
                + ":"
                + snapshot.stage()
                + ":"
                + snapshot.updatedAt()
                + ":"
                + OperationProgressPresenter.displayPercent(snapshot);
    }

    private String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }
}
