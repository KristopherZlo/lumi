package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.RefConflictException;
import io.github.lumi.storage.repository.VersionTagRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class SaveService implements SavePublisher {
    private final WorldObjectRepository objects;
    private final MerkleTreeEditor trees;
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final VersionTagRepository tags;
    private final OriginStore origins;

    public SaveService(
            WorldObjectRepository objects,
            MerkleTreeEditor trees,
            CommitRepository commits,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            VersionTagRepository tags,
            OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.trees = Objects.requireNonNull(trees, "trees");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @Override
    public SaveResult save(SaveRequest request, CapturedWorldState captured) throws IOException {
        return save(request, captured, ignored -> { });
    }

    @Override
    public SaveResult save(
            SaveRequest request,
            CapturedWorldState captured,
            Consumer<SavePublicationProgress> progress) throws IOException {
        return save(request, captured, progress, ignored -> { });
    }

    @Override
    public SaveResult save(
            SaveRequest request,
            CapturedWorldState captured,
            Consumer<SavePublicationProgress> progress,
            SavePublicationCompletion completion) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(completion, "completion");
        Optional<CommitId> cleanReturn = reusableReturnPoint(request, captured);
        if (cleanReturn.isPresent()) {
            requireCurrent(request);
            progress.accept(SavePublicationProgress.indeterminate(
                    "Save: reusing current version"));
            completion.complete(captured.generations());
            return new SaveResult(
                    cleanReturn.orElseThrow(), request.expectedRef(),
                    captured.generations());
        }
        CommitId commitId = writeCommit(request, captured, progress);
        if (!request.tags().isEmpty()) {
            progress.accept(SavePublicationProgress.indeterminate(
                    "Save: writing version tags"));
            tags.replace(commitId, request.tags());
        }
        progress.accept(SavePublicationProgress.indeterminate("Save: publishing branch"));
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(),
                OperationKind.SAVE,
                OperationPhase.COMMIT_WRITTEN,
                new OperationTarget(
                        request.expectedRef().name(),
                        request.expectedRef().commit(),
                        request.expectedRef().revision(),
                        Optional.of(commitId),
                        Optional.empty()),
                Optional.of(captured.generations())));
        var branch = refs.compareAndSet(request.expectedRef(), commitId);
        journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        progress.accept(SavePublicationProgress.indeterminate(
                "Save: finalizing working index"));
        completion.complete(captured.generations());
        journal = journals.advance(journal, OperationPhase.COMPLETE);
        journals.clear(journal);
        return new SaveResult(commitId, branch, captured.generations());
    }

    private Optional<CommitId> reusableReturnPoint(
            SaveRequest request, CapturedWorldState captured) throws IOException {
        if (request.kind() != CommitKind.HIDDEN_RETURN
                || !captured.generations().generations().isEmpty()) {
            return Optional.empty();
        }
        Commit current = commits.read(request.expectedRef().commit());
        if (!current.workspaceId().equals(request.workspaceId())) {
            throw new IOException("Save workspace does not match the source branch");
        }
        if (!current.playerSpawns().equals(captured.playerSpawns())) {
            return Optional.empty();
        }
        return Optional.of(request.expectedRef().commit());
    }

    public SaveResult checkpoint(
            SaveRequest request, CapturedWorldState captured, BranchName hiddenRef)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(hiddenRef, "hiddenRef");
        if (!hiddenRef.value().startsWith("hidden/")) {
            throw new IllegalArgumentException("Checkpoint ref must be hidden");
        }
        Optional<CommitId> reusable = reusableReturnPoint(request, captured);
        CommitId commitId;
        if (reusable.isPresent()) {
            commitId = reusable.orElseThrow();
        } else {
            requireCurrent(request);
            commitId = writeCommit(request, captured);
        }
        requireCurrent(request);
        var branch = refs.create(hiddenRef, commitId);
        new RetentionService(commits, refs).pruneAfterPublication(16, branch);
        return new SaveResult(commitId, branch, captured.generations());
    }

    private CommitId writeCommit(SaveRequest request, CapturedWorldState captured)
            throws IOException {
        return writeCommit(request, captured, ignored -> { });
    }

    private CommitId writeCommit(
            SaveRequest request,
            CapturedWorldState captured,
            Consumer<SavePublicationProgress> progress) throws IOException {
        Commit parent = commits.read(request.expectedRef().commit());
        if (!parent.workspaceId().equals(request.workspaceId())) {
            throw new IOException("Save workspace does not match the source branch");
        }
        ObjectId tree;
        try (WorldObjectRepository.WriteBatch batch = objects.beginBatch()) {
            Set<HistoryKey> capturedKeys = captured.generations().generations().keySet();
            progress.accept(new SavePublicationProgress(
                    "Save: packing restore origins", 0, capturedKeys.size()));
            Set<ObjectId> packedOrigins = new HashSet<>();
            long completedOrigins = 0;
            for (HistoryKey key : capturedKeys) {
                Optional<ObjectId> origin = origins.read(key);
                origin.ifPresent(packedOrigins::add);
                progress.accept(new SavePublicationProgress(
                        "Save: packing restore origins",
                        ++completedOrigins, capturedKeys.size()));
            }
            batch.packExisting(packedOrigins);
            long capturedTotal = captured.sections().size() + (long) captured.entities().size();
            progress.accept(new SavePublicationProgress(
                    "Save: writing captured state", 0, capturedTotal));
            Map<HistoryKey, ObjectId> changes = batch.writeCaptured(
                    captured.sections(), captured.entities(),
                    completed -> progress.accept(new SavePublicationProgress(
                            "Save: writing captured state", completed, capturedTotal)));
            tree = trees.update(Optional.of(parent.tree()), changes, batch,
                    (completed, total) -> progress.accept(new SavePublicationProgress(
                            "Save: building history tree", completed, total)));
            progress.accept(SavePublicationProgress.indeterminate(
                    "Save: publishing object pack"));
            batch.publish();
        }
        progress.accept(SavePublicationProgress.indeterminate("Save: writing commit"));
        List<CommitId> parents = request.replacesHead()
                ? parent.parents() : List.of(request.expectedRef().commit());
        Commit commit = new Commit(
                tree,
                parents,
                request.author(),
                request.message(),
                request.timestamp(),
                request.workspaceId(),
                request.zoneId(),
                request.kind(),
                captured.statistics(),
                captured.playerSpawns());
        return commits.write(commit);
    }

    private void requireCurrent(SaveRequest request) throws IOException {
        var actual = refs.read(request.expectedRef().name()).orElseThrow(
                () -> new RefConflictException("Checkpoint source branch is missing"));
        if (!actual.equals(request.expectedRef())) {
            throw new RefConflictException("Checkpoint source branch changed");
        }
    }
}
