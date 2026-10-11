package io.github.lumi.network;

import io.github.lumi.domain.model.WorkingIndexPreview;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Publishes only the latest bounded preview while its scope and generation remain current. */
final class ZonePendingPreviewCommandHandler {
    private final ConcurrentHashMap<UUID, UUID> pending = new ConcurrentHashMap<>();
    private final Function<Throwable, String> failureMessage;
    private final ResultSender results;

    ZonePendingPreviewCommandHandler(Function<Throwable, String> failureMessage, ResultSender results) {
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
        this.results = Objects.requireNonNull(results, "results");
    }

    void start(ServerPlayer player, FabricDimensionRuntime runtime, HistoryCommandPayload request,
            ServerPlayNetworking.Context context) throws IOException {
        var scope = ZonePendingPreviewArgument.parse(request.argument());
        UUID workspace = runtime.activeWorkspaceId();
        String dimension = runtime.level().dimension().identifier().toString();
        long revision = runtime.pendingRevision();
        pending.put(player.getUUID(), request.requestId());
        runtime.zonePendingPreview(workspace, scope.zoneId(), scope.expectedRevision(),
                ZonePendingPreviewPayload.MAX_BLOCKS).whenComplete((preview, failed) ->
                context.server().execute(() -> {
                    if (!pending.remove(player.getUUID(), request.requestId())
                            || context.server().getPlayerList().getPlayer(player.getUUID()) != player) return;
                    String error = failed == null ? "" : failureMessage.apply(failed);
                    try {
                        var active = runtime.activeRef();
                        if (error.isEmpty() && (!workspace.equals(runtime.activeWorkspaceId())
                                || !player.level().dimension().equals(runtime.level().dimension())
                                || !active.commit().equals(request.expectedCommit())
                                || active.revision() != request.expectedRevision()
                                || runtime.pendingRevision() != revision)) {
                            error = "History context changed; preview again";
                        }
                    } catch (IOException | IllegalStateException changed) {
                        error = failureMessage.apply(changed);
                    }
                    results.send(player, response(request, dimension, workspace, scope,
                            revision, preview, error));
                }));
    }

    static ZonePendingPreviewPayload response(HistoryCommandPayload request, String dimension,
            UUID workspace, ZonePendingPreviewArgument scope, long pendingRevision,
            WorkingIndexPreview preview, String error) {
        return new ZonePendingPreviewPayload(request.requestId(), dimension, workspace,
                request.expectedCommit(), request.expectedRevision(), scope.zoneId(),
                scope.expectedRevision(), pendingRevision,
                error.isEmpty() ? preview.blocks().stream().map(point ->
                        new HistorySnapshotPayload.PendingBlock(point.x(), point.y(), point.z())).toList()
                        : List.of(), error);
    }

    void cleanupPlayer(UUID player) { pending.remove(player); }
    void clear() { pending.clear(); }

    @FunctionalInterface
    interface ResultSender { void send(ServerPlayer player, ZonePendingPreviewPayload payload); }
}
