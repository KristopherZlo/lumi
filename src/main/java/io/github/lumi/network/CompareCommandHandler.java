package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Owns cancellable Compare jobs and their bounded wire results. */
final class CompareCommandHandler {
    private static final int MAX_MATERIALS = 128;
    private final CompareRequestRegistry jobs = new CompareRequestRegistry();
    private final Function<Throwable, String> failureMessage;
    private final ResultSender results;

    CompareCommandHandler(
            Function<Throwable, String> failureMessage,
            ResultSender results) {
        this.failureMessage = Objects.requireNonNull(
                failureMessage, "failureMessage");
        this.results = Objects.requireNonNull(results, "results");
    }

    void start(
            ServerPlayer player,
            FabricDimensionRuntime runtime,
            HistoryCommandPayload payload,
            ServerPlayNetworking.Context context) throws IOException {
        CommitId before;
        CommitId after;
        CompareRequestRegistry.Job job;
        if (payload.kind() == HistoryCommandPayload.Kind.ZONE_COMPARE) {
            ZoneCompareArgument argument = ZoneCompareArgument.parse(payload.argument());
            before = argument.before();
            after = argument.after();
            job = jobs.start(
                    payload.requestId(), player.getUUID(),
                    cancelled -> runtime.compare(
                            before, after, argument.zoneId(), cancelled));
        } else {
            CompareArgument argument = CompareArgument.parse(payload.argument());
            before = argument.before();
            after = argument.after();
            job = jobs.start(
                    payload.requestId(), player.getUUID(),
                    cancelled -> runtime.compare(before, after, cancelled));
        }
        String dimension = runtime.level().dimension().identifier().toString();
        job.future().whenComplete((summary, failure) ->
                context.server().execute(() -> finish(
                        player, payload.requestId(), dimension,
                        before, after, job, summary, failure)));
    }

    private void finish(
            ServerPlayer player,
            UUID requestId,
            String dimension,
            CommitId before,
            CommitId after,
            CompareRequestRegistry.Job job,
            ComparisonSummary summary,
            Throwable failure) {
        if (!jobs.finish(requestId, job) || job.cancelled().get()) {
            return;
        }
        CompareResultPayload result = failure == null
                ? success(requestId, dimension, summary)
                : new CompareResultPayload(
                        requestId, dimension, before, after, 0, 0,
                        java.util.List.of(), failureMessage.apply(failure));
        results.send(player, result);
    }

    void cancelOwned(UUID requestId, UUID playerId) {
        jobs.cancelOwned(requestId, playerId);
    }

    void cleanupPlayer(UUID playerId) {
        jobs.cancelPlayer(playerId);
    }

    void clear() {
        jobs.clear();
    }

    private static CompareResultPayload success(
            UUID requestId, String dimension, ComparisonSummary summary) {
        var materials = summary.materials().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .limit(MAX_MATERIALS)
                .map(entry -> new CompareResultPayload.Material(
                        entry.getKey(), entry.getValue().before(), entry.getValue().after()))
                .toList();
        return new CompareResultPayload(
                requestId, dimension, summary.before(), summary.after(),
                summary.changedSections(), summary.changedEntityChunks(),
                summary.sectionPreview().stream()
                        .map(section -> new CompareResultPayload.ChangedSection(
                                section.chunkX(), section.sectionY(), section.chunkZ()))
                        .toList(),
                materials, "");
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ServerPlayer player, CompareResultPayload payload);
    }
}
