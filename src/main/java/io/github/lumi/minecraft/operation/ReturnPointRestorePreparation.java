package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.service.BlockOnlyRestoreService;
import io.github.lumi.domain.service.ForwardHistoryService;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Prepares a journaled Restore from an already durable return point. */
public final class ReturnPointRestorePreparation {
    private final RestoreService restores;
    private final BlockOnlyRestoreService blockOnlyRestores;
    private final WorldStateApply world;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final ForwardHistoryService forwardHistory;
    private final RestoreStateListener stateListener;
    private final Executor background;

    public ReturnPointRestorePreparation(
            RestoreService restores,
            BlockOnlyRestoreService blockOnlyRestores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            ForwardHistoryService forwardHistory,
            Executor background) {
        this(restores, blockOnlyRestores, world, refs, journals, forwardHistory,
                RestoreStateListener.NONE, background);
    }

    public ReturnPointRestorePreparation(
            RestoreService restores,
            BlockOnlyRestoreService blockOnlyRestores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            ForwardHistoryService forwardHistory,
            RestoreStateListener stateListener,
            Executor background) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.blockOnlyRestores = Objects.requireNonNull(
                blockOnlyRestores, "blockOnlyRestores");
        this.world = Objects.requireNonNull(world, "world");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.forwardHistory = Objects.requireNonNull(forwardHistory, "forwardHistory");
        this.stateListener = Objects.requireNonNull(stateListener, "stateListener");
        this.background = Objects.requireNonNull(background, "background");
    }

    public CompletableFuture<RestoreOperation> prepareCheckpoint(
            BranchRef source,
            SaveResult checkpoint,
            CommitId target,
            UUID operationId,
            RestorePublication publication,
            Consumer<OperationProgress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return prepareNow(
                        source, checkpoint, target, operationId,
                        publication, progress);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public RestorePrewarm prewarmCheckpoint(
            BranchRef source,
            CommitId target,
            Consumer<OperationProgress> progress) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(progress, "progress");
        CompletableFuture<RestoreOperation.PrewarmedRestore> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        requireSource(source);
                        PreparedRestore restore = restores.prepare(
                                source, source.commit(), target,
                                value -> publishProgress(
                                        progress, "Restore prewarm: comparing", value));
                        return RestoreOperation.prewarm(restore, world, progress);
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background);
        return new RestorePrewarm(source, target, restores, future);
    }

    public CompletableFuture<RestoreOperation> prepareCheckpoint(
            BranchRef source,
            SaveResult checkpoint,
            CommitId target,
            UUID operationId,
            RestorePublication publication,
            Consumer<OperationProgress> progress,
            RestorePrewarm prewarm) {
        Objects.requireNonNull(prewarm, "prewarm");
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireSource(source);
                var prepared = prewarm.claim(checkpoint);
                if (prepared.isPresent()) {
                    forwardHistory.retain(source);
                    return RestoreOperation.startCheckpointed(
                            prepared.orElseThrow(), world, publication, journals,
                            operationId, stateListener, checkpoint.commitId(),
                            checkpoint.capturedGenerations());
                }
                return prepareNow(
                        source, checkpoint, target, operationId,
                        publication, progress);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    public CompletableFuture<RestoreOperation> prepareBlockOnlyCheckpoint(
            BranchRef source,
            SaveResult checkpoint,
            CommitId target,
            CommitAuthor author,
            Instant timestamp,
            UUID operationId,
            RestorePublication publication,
            Consumer<OperationProgress> progress) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(timestamp, "timestamp");
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireSource(source);
                CommitId composite = blockOnlyRestores.compose(
                        checkpoint.commitId(), target, author, timestamp,
                        value -> publishProgress(
                                progress, "Restore: preserving current entities", value));
                return prepareNow(
                        source, checkpoint, composite, operationId,
                        publication, progress);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    private RestoreOperation prepareNow(
            BranchRef source,
            SaveResult returnPoint,
            CommitId target,
            UUID operationId,
            RestorePublication publication,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(returnPoint, "returnPoint");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(progress, "progress");
        requireSource(source);
        forwardHistory.retain(source);
        long diffStarted = System.nanoTime();
        var restore = restores.prepare(
                source, returnPoint.commitId(), target,
                value -> publishProgress(progress, "Restore: comparing", value));
        long diffMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - diffStarted);
        long decodeStarted = System.nanoTime();
        RestoreOperation operation = RestoreOperation.startCheckpointed(
                restore, world, publication, journals,
                operationId, stateListener, returnPoint.commitId(),
                returnPoint.capturedGenerations(), progress);
        long decodeMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - decodeStarted);
        LumiMod.LOGGER.info(
                "Lumi prepared Restore in {} ms: diff={} ms, decode={} ms, "
                        + "targetSections={}, returnSections={}, "
                        + "targetEntityChunks={}, returnEntityChunks={}",
                diffMillis + decodeMillis, diffMillis, decodeMillis,
                restore.sections().size(), restore.returnSections().size(),
                restore.entities().size(), restore.returnEntities().size());
        return operation;
    }

    private void requireSource(BranchRef source) throws IOException {
        Objects.requireNonNull(source, "source");
        BranchRef current = refs.read(source.name()).orElseThrow(
                () -> new IOException("Restore source branch is missing"));
        if (!current.equals(source)) {
            throw new IOException("Restore source branch changed");
        }
    }

    private static void publishProgress(
            Consumer<OperationProgress> target,
            String phase,
            RestoreService.PreparationProgress progress) {
        target.accept(new OperationProgress(
                phase + " region " + progress.regionIndex()
                        + "/" + progress.regionTotal(),
                progress.chunkCompleted(), progress.chunkTotal()));
    }
}
