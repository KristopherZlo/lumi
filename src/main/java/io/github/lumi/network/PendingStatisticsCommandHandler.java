package io.github.lumi.network;

import io.github.lumi.minecraft.operation.DimensionMutation;
import io.github.lumi.minecraft.operation.PendingStatisticsOperation;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Queues exact pending-statistics capture and publishes one correlated result. */
final class PendingStatisticsCommandHandler {
    private final Function<Throwable, String> failureMessage;
    private final ResultSender results;

    PendingStatisticsCommandHandler(
            Function<Throwable, String> failureMessage,
            ResultSender results) {
        this.failureMessage = Objects.requireNonNull(
                failureMessage, "failureMessage");
        this.results = Objects.requireNonNull(results, "results");
    }

    void start(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            PendingStatisticsRequestPayload request,
            ServerPlayNetworking.Context context) {
        try {
            var active = runtime.activeRef();
            UUID workspace = runtime.activeWorkspaceId();
            String dimension =
                    runtime.level().dimension().identifier().toString();
            if (!request.dimensionId().equals(dimension)
                    || !request.workspaceId().equals(workspace)
                    || !request.head().equals(active.commit())
                    || request.revision() != active.revision()
                    || request.pendingRevision() != runtime.pendingRevision()) {
                results.send(player, PendingStatisticsPayload.failure(
                        request, "History context changed; refresh"));
                return;
            }
            runtime.startPendingStatistics(active, operation ->
                    complete(player, runtime, request, operation, context));
        } catch (IOException | IllegalStateException failed) {
            results.send(player, PendingStatisticsPayload.failure(
                    request, failureMessage.apply(failed)));
        }
    }

    private void complete(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            PendingStatisticsRequestPayload request,
            DimensionMutation outcome,
            ServerPlayNetworking.Context context) {
        if (context.server().getPlayerList()
                .getPlayer(player.getUUID()) != player) {
            return;
        }
        Optional<io.github.lumi.domain.service.PendingChangeStatisticsService.Result>
                calculated = outcome instanceof PendingStatisticsOperation operation
                        ? operation.result() : Optional.empty();
        String error = outcome.failure()
                .map(failureMessage)
                .orElseGet(() -> calculated.isPresent()
                        ? "" : "Pending statistics are unavailable");
        if (error.isEmpty()
                && request.pendingRevision() != runtime.pendingRevision()) {
            error = "Pending changes moved while statistics were calculated";
        }
        if (!error.isEmpty()) {
            results.send(player, PendingStatisticsPayload.failure(
                    request, error));
            return;
        }
        var result = calculated.orElseThrow();
        results.send(player, new PendingStatisticsPayload(
                request.requestId(), request.dimensionId(),
                request.workspaceId(), request.head(), request.revision(),
                request.pendingRevision(), result.workspace(), result.zones(), ""));
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ServerPlayer player, PendingStatisticsPayload payload);
    }
}
