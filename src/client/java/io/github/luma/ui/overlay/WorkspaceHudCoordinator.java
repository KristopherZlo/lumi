package io.github.luma.ui.overlay;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.WorkspaceHudSnapshot;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.controller.WorkspaceHudController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Coordinates persistent HUD and action bar feedback for the active workspace.
 */
public final class WorkspaceHudCoordinator {

    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath(LumaMod.MOD_ID, "workspace_hud");
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private static final int TERMINAL_DISPLAY_TICKS = 40;
    private static final WorkspaceHudCoordinator INSTANCE = new WorkspaceHudCoordinator();

    private final WorkspaceHudController controller = new WorkspaceHudController();
    private WorkspaceHudSnapshot workspaceSnapshot;
    private OperationSnapshot activeOperation;
    private OperationSnapshot retainedTerminalOperation;
    private String retainedTerminalFingerprint = "";
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

        this.refreshCooldown -= 1;
        if (this.refreshCooldown <= 0 || this.workspaceSnapshot == null) {
            this.workspaceSnapshot = this.controller.loadCurrentWorkspaceSnapshot();
            this.refreshCooldown = REFRESH_INTERVAL_TICKS;
        }

        OperationSnapshot current = this.workspaceSnapshot == null ? null : this.workspaceSnapshot.operationSnapshot();
        if (current != null && !current.terminal()) {
            this.activeOperation = current;
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
        this.activeOperation = null;
        this.retainedTerminalOperation = null;
        this.retainedTerminalFingerprint = "";
        this.retainedTerminalTicks = 0;
        this.refreshCooldown = 0;
        this.actionbarOwned = false;
    }

    private void updateActionbar(Minecraft client, OperationSnapshot snapshot) {
        client.gui.setOverlayMessage(this.buildActionbar(snapshot), false);
        this.actionbarOwned = true;
    }

    private Component buildActionbar(OperationSnapshot snapshot) {
        int percent = OperationProgressPresenter.displayPercent(snapshot);
        Component stage = Component.literal(this.humanStage(snapshot.stage()))
                .withStyle(this.stageColor(snapshot.stage()));
        Component progressBar = Component.literal(this.progressBar(percent))
                .withStyle(this.stageColor(snapshot.stage()));
        return Component.empty()
                .append(Component.literal(this.humanLabel(snapshot.handle().label()) + " ").withStyle(ChatFormatting.WHITE))
                .append(stage)
                .append(Component.literal(" "))
                .append(progressBar)
                .append(Component.literal(" " + percent + "%").withStyle(ChatFormatting.WHITE));
    }

    private void render(GuiGraphics drawContext, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || this.workspaceSnapshot == null) {
            return;
        }

        String titleText = this.workspaceSnapshot.projectName() == null || this.workspaceSnapshot.projectName().isBlank()
                ? this.workspaceSnapshot.workspaceLabel()
                : this.workspaceSnapshot.projectName();
        String branchText = this.workspaceSnapshot.activeVariantId() == null || this.workspaceSnapshot.activeVariantId().isBlank()
                ? ""
                : "branch: " + this.workspaceSnapshot.activeVariantId();
        String plusText = "+" + this.workspaceSnapshot.plusCount();
        String minusText = "-" + this.workspaceSnapshot.minusCount();

        int titleWidth = client.font.width(titleText);
        int branchWidth = branchText.isBlank() ? 0 : client.font.width(branchText);
        int countersWidth = client.font.width(plusText) + 8 + client.font.width(minusText);
        int boxWidth = Math.max(titleWidth, Math.max(branchWidth, countersWidth)) + 12;
        int x = drawContext.guiWidth() - boxWidth - 8;
        int y = 8;

        drawContext.fill(x, y, x + boxWidth, y + 34, 0x7A0B1016);
        drawContext.drawString(client.font, titleText, x + 6, y + 4, 0xFFF3F7FA, true);
        if (!branchText.isBlank()) {
            drawContext.drawString(client.font, branchText, x + 6, y + 14, 0xFF98A6B3, false);
        }
        drawContext.drawString(client.font, plusText, x + 6, y + 24, 0xFF69E38A, false);
        drawContext.drawString(client.font, minusText, x + 14 + client.font.width(plusText), y + 24, 0xFFFF7373, false);
    }

    private String fingerprint(OperationSnapshot snapshot) {
        return snapshot.handle().id() + ":" + snapshot.updatedAt();
    }

    private String humanLabel(String label) {
        if (label == null || label.isBlank()) {
            return "Operation";
        }

        String[] parts = label.split("[-_\\s]+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String humanStage(OperationStage stage) {
        return switch (stage) {
            case QUEUED -> "Queued";
            case PREPARING -> "Preparing";
            case WRITING -> "Writing";
            case APPLYING -> "Applying";
            case FINALIZING -> "Finalizing";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    private ChatFormatting stageColor(OperationStage stage) {
        return switch (stage) {
            case PREPARING, QUEUED -> ChatFormatting.GOLD;
            case WRITING -> ChatFormatting.BLUE;
            case APPLYING -> ChatFormatting.GREEN;
            case FINALIZING -> ChatFormatting.AQUA;
            case COMPLETED -> ChatFormatting.DARK_GREEN;
            case FAILED -> ChatFormatting.RED;
        };
    }

    private String progressBar(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = Math.max(0, Math.min(20, (int) Math.round((clamped / 100.0D) * 20.0D)));
        StringBuilder builder = new StringBuilder(22);
        builder.append('[');
        for (int index = 0; index < 20; index++) {
            builder.append(index < filled ? '=' : '-');
        }
        builder.append(']');
        return builder.toString();
    }
}
