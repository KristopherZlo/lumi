package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
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

/** Makes the hidden return ref durable before preparing a journaled Restore. */
public final class ReturnPointRestorePreparation {
    private final RestoreService restores;
    private final WorldStateApply world;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final RestoreStateListener stateListener;
    private final Executor background;

    public ReturnPointRestorePreparation(
            RestoreService restores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            Executor background) {
        this(restores, world, refs, journals, RestoreStateListener.NONE, background);
    }

    public ReturnPointRestorePreparation(
            RestoreService restores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            RestoreStateListener stateListener,
            Executor background) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.world = Objects.requireNonNull(world, "world");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
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
        Objects.requireNonNull(returnPoint, "returnPoint");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hiddenRef, "hiddenRef");
        Objects.requireNonNull(operationId, "operationId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                refs.create(hiddenRef, returnPoint.commitId());
                var restore = includeEntities
                        ? restores.prepare(returnPoint.branchRef(), target)
                        : restores.prepareWithoutEntities(returnPoint.branchRef(), target);
                return includeEntities
                        ? RestoreOperation.start(
                                restore, world, refs, journals, operationId, stateListener)
                        : RestoreOperation.startWithoutEntities(
                                restore, world, refs, journals, operationId, stateListener);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }
}
