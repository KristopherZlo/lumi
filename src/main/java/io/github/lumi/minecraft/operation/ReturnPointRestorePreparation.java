package io.github.lumi.minecraft.operation;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.service.ForwardHistoryService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Makes the hidden return ref durable before preparing a journaled Restore. */
public final class ReturnPointRestorePreparation {
    private final RestoreService restores;
    private final WorldStateApply world;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final ForwardHistoryService forwardHistory;
    private final RestoreStateListener stateListener;
    private final Executor background;

    public ReturnPointRestorePreparation(
            RestoreService restores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            ForwardHistoryService forwardHistory,
            Executor background) {
        this(restores, world, refs, journals, forwardHistory,
                RestoreStateListener.NONE, background);
    }

    public ReturnPointRestorePreparation(
            RestoreService restores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            ForwardHistoryService forwardHistory,
            RestoreStateListener stateListener,
            Executor background) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.world = Objects.requireNonNull(world, "world");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.forwardHistory = Objects.requireNonNull(forwardHistory, "forwardHistory");
        this.stateListener = Objects.requireNonNull(stateListener, "stateListener");
        this.background = Objects.requireNonNull(background, "background");
    }

    public CompletableFuture<RestoreOperation> prepare(
            SaveResult returnPoint,
            CommitId target,
            BranchName hiddenRef,
            UUID operationId) {
        return prepare(returnPoint, target, hiddenRef, operationId, true);
    }

    public CompletableFuture<RestoreOperation> prepare(
            SaveResult returnPoint,
            CommitId target,
            BranchName hiddenRef,
            UUID operationId,
            boolean includeEntities) {
        return prepare(returnPoint, target, hiddenRef, operationId,
                includeEntities, ignored -> { });
    }

    public CompletableFuture<RestoreOperation> prepare(
            SaveResult returnPoint,
            CommitId target,
            BranchName hiddenRef,
            UUID operationId,
            boolean includeEntities,
            Consumer<OperationProgress> progress) {
        Objects.requireNonNull(returnPoint, "returnPoint");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hiddenRef, "hiddenRef");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(progress, "progress");
        return CompletableFuture.supplyAsync(() -> {
            try {
                refs.create(hiddenRef, returnPoint.commitId());
                forwardHistory.retain(returnPoint.branchRef());
                long diffStarted = System.nanoTime();
                var restore = includeEntities
                        ? restores.prepare(returnPoint.branchRef(), target,
                                value -> publishDiffProgress(progress, value))
                        : restores.prepareWithoutEntities(returnPoint.branchRef(), target,
                                value -> publishDiffProgress(progress, value));
                long diffMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - diffStarted);
                long decodeStarted = System.nanoTime();
                RestoreOperation operation = includeEntities
                        ? RestoreOperation.start(
                                restore, world, refs, journals, operationId,
                                stateListener, progress)
                        : RestoreOperation.startWithoutEntities(
                                restore, world, refs, journals, operationId,
                                stateListener, progress);
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
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    private static void publishDiffProgress(
            Consumer<OperationProgress> target,
            RestoreService.PreparationProgress progress) {
        target.accept(new OperationProgress(
                "Restore: comparing region " + progress.regionIndex()
                        + "/" + progress.regionTotal(),
                progress.chunkCompleted(), progress.chunkTotal()));
    }
}
