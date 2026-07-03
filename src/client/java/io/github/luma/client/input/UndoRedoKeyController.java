package io.github.luma.client.input;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.UndoRedoAction;
import io.github.luma.domain.model.UndoRedoActionStack;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.UndoRedoService;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.capture.UndoRedoHistoryManager;
import io.github.luma.minecraft.capture.WorldMutationContext;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.ActionBarMessagePresenter;
import io.github.luma.ui.controller.ClientProjectAccess;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
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
    private final AxiomUndoRedoBridge axiomUndoRedoBridge = new AxiomUndoRedoBridge();
    private final UndoRedoRequestQueue requestQueue = new UndoRedoRequestQueue();
    private final UndoRedoFailurePolicy failurePolicy = new UndoRedoFailurePolicy();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public void undo(Minecraft client) {
        this.enqueue(client, UndoRedoRequestQueue.Intent.UNDO);
    }

    public void redo(Minecraft client) {
        this.enqueue(client, UndoRedoRequestQueue.Intent.REDO);
    }

    public TickResult tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            this.requestQueue.clear();
            return TickResult.idle();
        }
        if (!this.requestQueue.hasAnyPending()) {
            return TickResult.idle();
        }

        UndoRedoRequestQueue.Intent intent = null;
        UndoRedoRequestQueue.Scope scope = null;
        try {
            CurrentTarget target = this.currentTarget(client);
            scope = target.scope();
            if (this.requestQueue.isEmpty(scope)) {
                return TickResult.idle();
            }
            if (this.worldOperationManager.hasActiveOperation(target.level().getServer())) {
                return TickResult.idle();
            }
            intent = this.requestQueue.poll(scope);
            if (intent == null) {
                return TickResult.idle();
            }
            return this.start(client, target, intent == UndoRedoRequestQueue.Intent.UNDO);
        } catch (Exception exception) {
            String statusKey = this.failurePolicy.statusKey(exception, intent != UndoRedoRequestQueue.Intent.REDO);
            if (intent != null && scope != null && this.failurePolicy.shouldRetry(statusKey)) {
                this.requestQueue.offerFirst(scope, intent);
                return TickResult.idle();
            }
            client.gui.setOverlayMessage(this.failurePolicy.statusMessage(statusKey), false);
            return TickResult.terminalNoOperation();
        }
    }

    private void enqueue(Minecraft client, UndoRedoRequestQueue.Intent intent) {
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        try {
            CurrentTarget target = this.currentTarget(client);
            if (!this.requestQueue.offer(target.scope(), intent)) {
                client.gui.setOverlayMessage(ActionBarMessagePresenter.warning("luma.status.undo_redo_queue_full"), false);
                return;
            }
            client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                    intent == UndoRedoRequestQueue.Intent.UNDO
                            ? "luma.status.undo_queued"
                            : "luma.status.redo_queued"
            ), false);
        } catch (Exception exception) {
            client.gui.setOverlayMessage(this.failurePolicy.statusMessage(this.failurePolicy.statusKey(
                    exception,
                    intent != UndoRedoRequestQueue.Intent.REDO
            )), false);
        }
    }

    private TickResult start(Minecraft client, CurrentTarget target, boolean undo) {
        if (client == null || client.player == null || client.level == null) {
            return TickResult.idle();
        }

        try {
            ServerLevel level = target.level();
            BuildProject project = target.project();
            if (this.tryNativeExternalUndoRedo(client, level, project, undo)) {
                client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                        undo ? "luma.status.native_undo_started" : "luma.status.native_redo_started"
                ), false);
                return TickResult.terminalNoOperation();
            }
            OperationHandle handle;
            String actor = this.playerActor(client);
            if (undo) {
                handle = this.undoRedoService.undo(level, project.name(), actor);
            } else {
                handle = this.undoRedoService.redo(level, project.name(), actor);
            }
            client.gui.setOverlayMessage(ActionBarMessagePresenter.info(
                    undo ? "luma.status.undo_started" : "luma.status.redo_started"
            ), false);
            return TickResult.started(handle);
        } catch (Exception exception) {
            String statusKey = this.failurePolicy.statusKey(exception, undo);
            if (this.failurePolicy.shouldRetry(statusKey)) {
                this.requestQueue.offerFirst(target.scope(), undo
                        ? UndoRedoRequestQueue.Intent.UNDO
                        : UndoRedoRequestQueue.Intent.REDO);
                return TickResult.idle();
            }
            client.gui.setOverlayMessage(this.failurePolicy.statusMessage(statusKey), false);
            return TickResult.terminalNoOperation();
        }
    }

    private ServerLevel currentLevel(Minecraft client) {
        var server = ClientProjectAccess.requireSingleplayerServer(client);
        ServerLevel level = server.getLevel(client.level.dimension());
        return level == null ? server.overworld() : level;
    }

    private CurrentTarget currentTarget(Minecraft client) throws Exception {
        ServerLevel level = this.currentLevel(client);
        BuildProject project = this.projectService.findWorldProject(level)
                .orElseThrow(() -> new IllegalArgumentException("No active Lumi workspace in this dimension"));
        return new CurrentTarget(
                level,
                project,
                new UndoRedoRequestQueue.Scope(
                        level.dimension().identifier().toString(),
                        project.id().toString()
                )
        );
    }

    private boolean tryNativeExternalUndoRedo(
            Minecraft client,
            ServerLevel level,
            BuildProject project,
            boolean undo
    ) throws Exception {
        String actor = this.playerActor(client);
        UndoRedoActionStack.Selection selection = undo
                ? this.historyManager.selectUndo(project.id().toString(), actor)
                : this.historyManager.selectRedo(project.id().toString(), actor);
        if (selection == null) {
            return false;
        }

        UndoRedoAction action = selection.action();
        ExternalUndoRedoPolicy.Decision decision = this.externalUndoRedoPolicy.decisionForAction(
                action.actor(),
                action.id()
        );
        if (decision == ExternalUndoRedoPolicy.Decision.LUMI_REPLAY) {
            return false;
        }

        this.captureManager.drainUndoRedoStabilization(level.getServer(), project.id().toString());
        selection = undo
                ? this.historyManager.selectUndo(project.id().toString(), actor)
                : this.historyManager.selectRedo(project.id().toString(), actor);
        if (selection == null) {
            return false;
        }
        action = selection.action();
        decision = this.externalUndoRedoPolicy.decisionForAction(
                action.actor(),
                action.id()
        );
        if (decision == ExternalUndoRedoPolicy.Decision.LUMI_REPLAY) {
            return false;
        }

        if (decision == ExternalUndoRedoPolicy.Decision.AXIOM_NATIVE_HOOK) {
            LumaMod.LOGGER.info(
                    "Delegating {} for Axiom action {} by {} to native Axiom history",
                    undo ? "undo" : "redo",
                    action.id(),
                    action.actor()
            );
            AxiomUndoRedoBridge.DispatchResult dispatchResult = this.axiomUndoRedoBridge.dispatch(undo);
            if (!dispatchResult.dispatched()) {
                LumaMod.LOGGER.info(
                        "Native Axiom {} unavailable for action {}: {}; falling back to Lumi replay",
                        undo ? "undo" : "redo",
                        action.id(),
                        dispatchResult.fallbackReason()
                );
                return false;
            }
            try {
                this.awaitQueuedServerWork(level.getServer());
            } finally {
                dispatchResult.clearUnconsumedReplayExpectation();
            }
            LumaMod.LOGGER.info(
                    "Native Axiom {} completed for action {}; applying Lumi history adjustment",
                    undo ? "undo" : "redo",
                    action.id()
            );
        } else {
            this.dispatchNativeToolCommand(client, level.getServer(), undo ? "undo" : "redo");
        }

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

    private void awaitQueuedServerWork(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            server.submit(() -> null).join();
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
        this.captureManager.applyLiveActionAdjustments(
                level.getServer(),
                project.id().toString(),
                blockChanges,
                entityChanges,
                actor,
                Instant.now()
        );
    }

    private String playerActor(Minecraft client) {
        return client == null || client.player == null ? "player" : client.player.getName().getString();
    }

    private record CurrentTarget(ServerLevel level, BuildProject project, UndoRedoRequestQueue.Scope scope) {
    }

    public record TickResult(OperationHandle operationHandle, boolean terminalWithoutOperation) {

        public static TickResult idle() {
            return new TickResult(null, false);
        }

        public static TickResult started(OperationHandle operationHandle) {
            return new TickResult(operationHandle, false);
        }

        public static TickResult terminalNoOperation() {
            return new TickResult(null, true);
        }
    }
}
