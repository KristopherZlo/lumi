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
    private final Executor background;

    public ReturnPointRestorePreparation(
            RestoreService restores,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            Executor background) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.world = Objects.requireNonNull(world, "world");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.background = Objects.requireNonNull(background, "background");
    }

    public CompletableFuture<RestoreOperation> prepare(
            SaveResult returnPoint,
            CommitId target,
            BranchName hiddenRef,
            UUID operationId) {
        Objects.requireNonNull(returnPoint, "returnPoint");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hiddenRef, "hiddenRef");
        Objects.requireNonNull(operationId, "operationId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                refs.create(hiddenRef, returnPoint.commitId());
                return RestoreOperation.start(
                        restores.prepare(returnPoint.branchRef(), target),
                        world, refs, journals, operationId);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }
}
