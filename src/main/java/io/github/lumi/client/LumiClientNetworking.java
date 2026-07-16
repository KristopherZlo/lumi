package io.github.lumi.client;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistoryCommandPayload;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import io.github.lumi.network.OperationCancelPayload;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Thin client sender/receiver; all history decisions stay on the server. */
public final class LumiClientNetworking {
    private final ClientHistoryStore history;

    public LumiClientNetworking(ClientHistoryStore history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                HistorySnapshotPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> history.accept(payload)));
        ClientPlayNetworking.registerGlobalReceiver(
                OperationEventPayload.TYPE, (payload, context) ->
                        context.client().execute(() -> history.accept(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> history.clear());
    }

    public UUID save(String message) {
        return send(HistoryCommandPayload.Kind.SAVE,
                Objects.requireNonNull(message, "message"));
    }

    public UUID restore(CommitId target) {
        return send(HistoryCommandPayload.Kind.RESTORE,
                Objects.requireNonNull(target, "target").hex());
    }

    public UUID quickRollback() {
        return send(HistoryCommandPayload.Kind.QUICK_ROLLBACK, "");
    }

    public UUID undo() {
        return send(HistoryCommandPayload.Kind.UNDO, "");
    }

    public UUID redo() {
        return send(HistoryCommandPayload.Kind.REDO, "");
    }

    public UUID switchBranch(String branchName) {
        return send(HistoryCommandPayload.Kind.BRANCH_SWITCH,
                Objects.requireNonNull(branchName, "branchName"));
    }

    public UUID cancel(UUID originalRequest) {
        var event = history.state().events().get(
                Objects.requireNonNull(originalRequest, "originalRequest"));
        if (event == null || event.ticketId().isEmpty()) {
            throw new IllegalStateException("Operation has no cancellable queue ticket");
        }
        if (!ClientPlayNetworking.canSend(OperationCancelPayload.TYPE)) {
            throw new IllegalStateException("The connected server does not support queue cancellation");
        }
        UUID requestId = UUID.randomUUID();
        ClientPlayNetworking.send(new OperationCancelPayload(
                requestId, event.ticketId().orElseThrow()));
        return requestId;
    }

    private UUID send(HistoryCommandPayload.Kind kind, String argument) {
        var snapshot = history.state().snapshot().orElseThrow(
                () -> new IllegalStateException("Lumi history has not synchronized yet"));
        if (!ClientPlayNetworking.canSend(HistoryCommandPayload.TYPE)) {
            throw new IllegalStateException("The connected server does not support Lumi history");
        }
        UUID requestId = UUID.randomUUID();
        ClientPlayNetworking.send(new HistoryCommandPayload(
                requestId, kind, argument, snapshot.head(), snapshot.revision()));
        return requestId;
    }
}
