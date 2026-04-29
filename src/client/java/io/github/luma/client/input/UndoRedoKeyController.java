package io.github.luma.client.input;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handles client key-driven undo and redo requests.
 */
public final class UndoRedoKeyController {

    private final ProjectService projectService = new ProjectService();
    private final UndoRedoService undoRedoService = new UndoRedoService();
    private final UndoRedoHistoryManager historyManager = UndoRedoHistoryManager.getInstance();
    private final HistoryCaptureManager captureManager = HistoryCaptureManager.getInstance();
    private final ExternalUndoRedoPolicy externalUndoRedoPolicy = new ExternalUndoRedoPolicy();

    public void undo(Minecraft client) {
        this.start(client, true);
    }

    public void redo(Minecraft client) {
        this.start(client, false);
    }

    private void start(Minecraft client, boolean undo) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }

        try {
            ServerLevel level = this.currentLevel(client);
            var project = this.projectService.findWorldProject(level)
                    .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
            if (this.tryNativeExternalUndoRedo(client, level, project, undo)) {
                client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                        undo ? "luma.status.native_undo_started" : "luma.status.native_redo_started"
                ), false);
                return;
            }
            if (undo) {
                this.undoRedoService.undo(level, project.name());
            } else {
                this.undoRedoService.redo(level, project.name());
            }
            client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                    undo ? "luma.status.undo_started" : "luma.status.redo_started"
            ), false);
        } catch (Exception exception) {
            client.gui.setOverlayMessage(this.statusMessage(this.statusKey(exception, undo)), false);
        }
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private boolean tryNativeExternalUndoRedo(
            Minecraft client,
            ServerLevel level,
            BuildProject project,
            boolean undo
    ) throws Exception {
        this.captureManager.drainUndoRedoStabilization(level.getServer(), project.id().toString());
        UndoRedoActionStack.Selection selection = undo
                ? this.historyManager.selectUndo(project.id().toString())
                : this.historyManager.selectRedo(project.id().toString());
        if (selection == null) {
            return false;
        }

        UndoRedoAction action = selection.action();
        ExternalUndoRedoPolicy.Decision decision = this.externalUndoRedoPolicy.decisionForActor(action.actor());
        if (decision == ExternalUndoRedoPolicy.Decision.LUMI_REPLAY) {
            return false;
        }
        if (decision == ExternalUndoRedoPolicy.Decision.AXIOM_OWN_UNDO) {
            throw new IllegalArgumentException("Axiom edits use Axiom's own undo command");
        }

        this.dispatchNativeToolCommand(client, level.getServer(), undo ? "undo" : "redo");
        if (undo) {
            this.historyManager.completeUndo(project.id().toString(), selection);
            this.applyPendingAdjustments(level, project, action.inverseChanges(), action.inverseEntityChanges(), action.actor());
        } else {
            this.historyManager.completeRedo(project.id().toString(), selection);
            this.applyPendingAdjustments(level, project, action.redoChanges(), action.redoEntityChanges(), action.actor());
        }
        return true;
    }

    private void dispatchNativeToolCommand(Minecraft client, MinecraftServer server, String command) {
        if (server == null || client == null || client.player == null) {
            throw new IllegalArgumentException("No active Lumi workspace in this dimension");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(client.player.getUUID());
        if (player == null) {
            throw new IllegalArgumentException("No active Lumi workspace in this dimension");
        }

        try {
            server.submit(() -> {
                WorldMutationContext.runWithCaptureSuppressed(() ->
                        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command)
                );
                return null;
            }).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private void applyPendingAdjustments(
            ServerLevel level,
            BuildProject project,
            List<StoredBlockChange> blockChanges,
            List<StoredEntityChange> entityChanges,
            String actor
    ) throws Exception {
        this.captureManager.applyUndoRedoAdjustments(
                level.getServer(),
                project.id().toString(),
                blockChanges,
                entityChanges,
                actor,
                Instant.now()
        );
    }

    private String statusKey(Exception exception, boolean undo) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("admin permissions") || message.contains("cheats enabled")) {
            return "luma.status.admin_required";
        }
        if (message.contains("Another world operation is already running")) {
            return "luma.status.world_operation_busy";
        }
        if (message.contains("Axiom edits use Axiom's own undo command")) {
            return "luma.status.axiom_undo_ignored";
        }
        if (message.contains("No Lumi action") || message.contains("No active Lumi workspace")) {
            return undo ? "luma.status.undo_unavailable" : "luma.status.redo_unavailable";
        }
        return "luma.status.operation_failed";
    }

    private Component statusMessage(String key) {
        if ("luma.status.operation_failed".equals(key)
                || "luma.status.world_operation_busy".equals(key)
                || "luma.status.admin_required".equals(key)) {
            return ActionBarMessagePresenter.error(key);
        }
        return ActionBarMessagePresenter.warning(key);
    }
}
