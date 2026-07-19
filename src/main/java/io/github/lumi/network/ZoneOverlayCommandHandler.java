package io.github.lumi.network;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.ZoneShellSnapshot;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Publishes only the latest player's off-thread zone shell query. */
final class ZoneOverlayCommandHandler {
    private final ConcurrentHashMap<UUID, UUID> pending =
            new ConcurrentHashMap<>();
    private final Function<Throwable, String> failureMessage;
    private final ResultSender results;

    ZoneOverlayCommandHandler(
            Function<Throwable, String> failureMessage,
            ResultSender results) {
        this.failureMessage = java.util.Objects.requireNonNull(
                failureMessage, "failureMessage");
        this.results = java.util.Objects.requireNonNull(results, "results");
    }

    void start(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) throws IOException {
        ZoneOverlayArgument argument = ZoneOverlayArgument.parse(
                payload.argument());
        UUID playerId = player.getUUID();
        UUID workspaceId = runtime.activeWorkspaceId();
        pending.put(playerId, payload.requestId());
        SectionKey center = new SectionKey(
                Math.floorDiv(player.getBlockX(), 16),
                Math.floorDiv(player.getBlockY(), 16),
                Math.floorDiv(player.getBlockZ(), 16));
        runtime.zoneOverlay(
                playerId, center,
                argument.mode() == ZoneOverlayArgument.Mode.ALL)
                .whenComplete((snapshot, failure) ->
                        context.server().execute(() -> finish(
                                player, payload.requestId(),
                                runtime.level().dimension().identifier().toString(),
                                workspaceId, snapshot, failure)));
    }

    private void finish(
            ServerPlayer player,
            UUID requestId,
            String dimension,
            UUID workspaceId,
            ZoneShellSnapshot snapshot,
            Throwable failure) {
        if (!pending.remove(player.getUUID(), requestId)) {
            return;
        }
        if (failure != null) {
            results.send(player, new ZoneOverlayPayload(
                    requestId, dimension, workspaceId,
                    0, true, Optional.empty(),
                    failureMessage.apply(failure)));
            return;
        }
        int total = snapshot.zones().stream()
                .mapToInt(zone -> Math.max(1,
                        (zone.faces().size()
                                + ZoneOverlayPayload.MAX_FACES - 1)
                                / ZoneOverlayPayload.MAX_FACES))
                .sum();
        if (total == 0) {
            results.send(player, new ZoneOverlayPayload(
                    requestId, dimension, snapshot.workspaceId(),
                    0, true, Optional.empty(), ""));
            return;
        }
        int batch = 0;
        for (ZoneShellSnapshot.ZoneShell zone : snapshot.zones()) {
            int packets = Math.max(1,
                    (zone.faces().size()
                            + ZoneOverlayPayload.MAX_FACES - 1)
                            / ZoneOverlayPayload.MAX_FACES);
            for (int packet = 0; packet < packets; packet++) {
                int start = packet * ZoneOverlayPayload.MAX_FACES;
                int end = Math.min(
                        start + ZoneOverlayPayload.MAX_FACES,
                        zone.faces().size());
                List<io.github.lumi.domain.model.ZoneShellFace> faces =
                        start == end ? List.of()
                                : zone.faces().subList(start, end);
                results.send(player, new ZoneOverlayPayload(
                        requestId, dimension, snapshot.workspaceId(),
                        batch, batch == total - 1,
                        Optional.of(new ZoneOverlayPayload.ZoneBatch(
                                zone.id(), zone.name(), zone.color(),
                                zone.revision(), zone.active(), zone.entered(),
                                faces)),
                        ""));
                batch++;
            }
        }
    }

    void cleanupPlayer(UUID playerId) {
        pending.remove(playerId);
    }

    void clear() {
        pending.clear();
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ServerPlayer player, ZoneOverlayPayload payload);
    }
}
