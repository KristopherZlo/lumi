package io.github.lumi.network;

import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ComparisonSummary;
import io.github.lumi.minecraft.runtime.FabricDimensionRuntime;
import java.io.IOException;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        String dimension = runtime.level().dimension().identifier().toString();
        AtomicInteger batchIndex = new AtomicInteger();
        if (payload.kind() == HistoryCommandPayload.Kind.ZONE_COMPARE) {
            ZoneCompareArgument argument = ZoneCompareArgument.parse(payload.argument());
            before = argument.before();
            after = argument.after();
            job = jobs.start(
                    payload.requestId(), player.getUUID(),
                    cancelled -> runtime.compare(
                            before, after, argument.zoneId(), cancelled,
                            blocks -> scheduleBatch(
                                    player, payload.requestId(), dimension,
                                    before, after, batchIndex.getAndIncrement(),
                                    blocks, context)));
        } else {
            CompareArgument argument = CompareArgument.parse(payload.argument());
            before = argument.before();
            after = argument.after();
            job = jobs.start(
                    payload.requestId(), player.getUUID(),
                    cancelled -> runtime.compare(
                            before, after, cancelled,
                            blocks -> scheduleBatch(
                                    player, payload.requestId(), dimension,
                                    before, after, batchIndex.getAndIncrement(),
                                    blocks, context)));
        }
        job.future().whenComplete((summary, failure) ->
                context.server().execute(() -> finish(
                        player, payload.requestId(), dimension,
                        before, after, batchIndex.get(), job, summary, failure)));
    }

    private void scheduleBatch(
            ServerPlayer player,
            UUID requestId,
            String dimension,
            CommitId before,
            CommitId after,
            int batchIndex,
            List<BlockChange> blocks,
            ServerPlayNetworking.Context context) {
        CompareResultPayload batch = blockBatch(
                requestId, dimension, before, after, batchIndex, blocks);
        context.server().execute(() -> {
            if (jobs.isOwned(requestId, player.getUUID())) {
                results.send(player, batch);
            }
        });
    }

    private void finish(
            ServerPlayer player,
            UUID requestId,
            String dimension,
            CommitId before,
            CommitId after,
            int batchIndex,
            CompareRequestRegistry.Job job,
            ComparisonSummary summary,
            Throwable failure) {
        if (!jobs.finish(requestId, job) || job.cancelled().get()) {
            return;
        }
        CompareResultPayload result = failure == null
                ? success(requestId, dimension, batchIndex, summary)
                : new CompareResultPayload(
                        requestId, dimension, before, after, 0, 0,
                        List.of(), List.of(), failureMessage.apply(failure),
                        batchIndex, true, List.of(), 0);
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

    static CompareResultPayload success(
            UUID requestId,
            String dimension,
            int batchIndex,
            ComparisonSummary summary) {
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
                materials, "", batchIndex, true, List.of(), summary.changedBlocks());
    }

    static CompareResultPayload blockBatch(
            UUID requestId,
            String dimension,
            CommitId before,
            CommitId after,
            int batchIndex,
            List<BlockChange> blocks) {
        return new CompareResultPayload(
                requestId, dimension, before, after, 0, 0,
                List.of(), List.of(), "", batchIndex, false, blocks, 0);
    }

    @FunctionalInterface
    interface ResultSender {
        void send(ServerPlayer player, CompareResultPayload payload);
    }
}
