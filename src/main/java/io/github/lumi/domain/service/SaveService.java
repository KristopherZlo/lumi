package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Commit;
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
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SaveService {
    private final WorldObjectRepository objects;
    private final MerkleTreeEditor trees;
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;

    public SaveService(
            WorldObjectRepository objects,
            MerkleTreeEditor trees,
            CommitRepository commits,
            BranchRefRepository refs,
            OperationJournalRepository journals) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.trees = Objects.requireNonNull(trees, "trees");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
    }

    public SaveResult save(SaveRequest request, CapturedWorldState captured) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(captured, "captured");
        Commit parent = commits.read(request.expectedRef().commit());
        Map<HistoryKey, ObjectId> changes = new HashMap<>();
        for (var section : captured.sections().entrySet()) {
            changes.put(section.getKey(), objects.write(section.getValue()));
        }
        for (var entities : captured.entities().entrySet()) {
            changes.put(entities.getKey(), objects.write(entities.getValue()));
        }
        ObjectId tree = trees.update(Optional.of(parent.tree()), changes);
        Commit commit = new Commit(
                tree,
                List.of(request.expectedRef().commit()),
                request.author(),
                request.message(),
                request.timestamp(),
                request.workspaceId(),
                request.zoneId(),
                request.kind(),
                captured.statistics());
        var commitId = commits.write(commit);
        OperationJournal journal = journals.create(new OperationJournal(
                UUID.randomUUID(),
                OperationKind.SAVE,
                OperationPhase.COMMIT_WRITTEN,
                new OperationTarget(
                        request.expectedRef().name(),
                        request.expectedRef().commit(),
                        request.expectedRef().revision(),
                        Optional.of(commitId),
                        Optional.empty())));
        var branch = refs.compareAndSet(request.expectedRef(), commitId);
        journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        journal = journals.advance(journal, OperationPhase.COMPLETE);
        journals.clear(journal);
        return new SaveResult(commitId, branch, captured.generations());
    }
}
