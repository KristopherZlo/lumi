package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.HistoryEntry;
import io.github.lumi.domain.model.HistoryPage;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Prepares bounded history pages off tick and publishes correlated results. */
final class HistoryPageCommandHandler {
    private final Function<Throwable, String> failureMessage;
    private final ResultSender results;

    HistoryPageCommandHandler(
            Function<Throwable, String> failureMessage,
            ResultSender results) {
        this.failureMessage = java.util.Objects.requireNonNull(
                failureMessage, "failureMessage");
        this.results = java.util.Objects.requireNonNull(results, "results");
    }

    void start(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryPageRequestPayload request,
            ServerPlayNetworking.Context context) {
        long started = System.nanoTime();
        LumiMod.LOGGER.info(
                "Lumi history page started: branch={}, zone={}, offset={}, limit={}",
                request.branch(), request.zoneId().orElse(null),
                request.offset(), request.limit());
        String actualDimension =
                runtime.level().dimension().identifier().toString();
        boolean browse = request.browsesDimension();
        var branch = request.branch();
        UUID activeWorkspace;
        try {
            activeWorkspace = runtime.activeWorkspaceId();
            if (browse) branch = runtime.activeRef().name();
        } catch (IOException failed) {
            results.send(player, failure(
                    request, failureMessage.apply(failed)));
            return;
        }
        if (!request.dimensionId().equals(actualDimension)
                || (!browse && !request.workspaceId().equals(activeWorkspace))) {
            results.send(player, failure(request, "History context changed; refresh"));
            return;
        }
        var future = request.zoneId().isPresent()
                ? runtime.zoneHistoryPage(
                        branch, request.zoneId().orElseThrow(),
                        request.offset(), request.limit(), request.query())
                        .thenApply(result -> new Result(
                                result.page(), result.branches().stream()
                                        .map(ref -> ref.name()).toList()))
                : runtime.historyPage(
                        branch, request.offset(), request.limit(),
                        request.query())
                        .thenApply(page -> new Result(page, java.util.List.of()));
        future.whenComplete((prepared, failure) -> {
            HistoryPagePayload result = failure == null
                    ? success(request, runtime, prepared)
                    : failure(request, failureMessage.apply(failure));
            LumiMod.LOGGER.info(
                    "Lumi history page prepared in {} ms: versions={}, error={}",
                    (System.nanoTime() - started) / 1_000_000,
                    result.versions().size(), result.error());
            context.server().execute(() -> {
                    if (context.server().getPlayerList()
                            .getPlayer(player.getUUID()) != player) {
                        return;
                    }
                    results.send(player, result);
                });
        });
    }

    private static HistoryPagePayload success(
            HistoryPageRequestPayload request,
            FabricDimensionRuntime runtime,
            Result result) {
        HistoryPage page = result.page();
        return new HistoryPagePayload(
                request.requestId(), request.dimensionId(),
                request.workspaceId(), request.branch(), request.zoneId(),
                page.offset(), page.hasMore(),
                page.entries().stream()
                        .map(entry -> version(runtime, entry))
                        .toList(),
                result.branches(),
                "");
    }

    private static HistorySnapshotPayload.Version version(
            FabricDimensionRuntime runtime, HistoryEntry entry) {
        return new HistorySnapshotPayload.Version(
                entry.id(),
                runtime.versionDisplayName(
                        entry.id(), entry.commit().message()),
                entry.commit().author().name(),
                entry.commit().timestamp().toEpochMilli(),
                entry.commit().kind(),
                runtime.versionTags(entry.id()),
                entry.commit().parents(),
                entry.commit().statistics(),
                entry.commit().zoneId());
    }

    private static HistoryPagePayload failure(
            HistoryPageRequestPayload request, String message) {
        return new HistoryPagePayload(
                request.requestId(), request.dimensionId(),
                request.workspaceId(), request.branch(), request.zoneId(),
                request.offset(), false, java.util.List.of(),
                Optional.ofNullable(message).orElse("History page failed"));
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ServerPlayer player, HistoryPagePayload payload);
    }

    private record Result(HistoryPage page, java.util.List<BranchName> branches) { }
}
