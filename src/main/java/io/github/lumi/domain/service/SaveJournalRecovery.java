package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.io.IOException;
import java.util.Objects;

/** Completes the only mutable publication step of an interrupted Save. */
public final class SaveJournalRecovery {
    private final CommitRepository commits;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final WorkingIndexRepository working;

    public SaveJournalRecovery(
            CommitRepository commits,
            BranchRefRepository refs,
            OperationJournalRepository journals) {
        this(commits, refs, journals, null);
    }

    public SaveJournalRecovery(
            CommitRepository commits,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            WorkingIndexRepository working) {
        this.commits = Objects.requireNonNull(commits, "commits");
        this.refs = Objects.requireNonNull(refs, "refs");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.working = working;
    }

    public void recover(OperationJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        if (journal.kind() != OperationKind.SAVE) {
            throw new IllegalArgumentException("Journal is not a Save");
        }
        var target = journal.target();
        var commit = target.target().orElseThrow(
                () -> new IOException("Interrupted Save has no commit target"));
        commits.read(commit);
        BranchRef expected = new BranchRef(
                target.branch(), target.expectedHead(), target.expectedRevision());
        BranchRef actual = refs.read(target.branch()).orElseThrow(
                () -> new IOException("Interrupted Save branch is missing"));
        BranchRef published = new BranchRef(
                target.branch(), commit, Math.addExact(target.expectedRevision(), 1));
        if (actual.equals(expected)) {
            if (journal.phase() != OperationPhase.COMMIT_WRITTEN) {
                throw new IOException("Save journal claims publication but ref is unchanged");
            }
            actual = refs.compareAndSet(expected, commit);
        }
        if (!actual.equals(published)) {
            throw new IOException("Interrupted Save ref changed independently");
        }
        if (journal.capturedGenerations().isPresent()) {
            if (working == null) {
                throw new IOException(
                        "Interrupted Save has a generation boundary but no working index");
            }
            working.clearCaptured(journal.capturedGenerations().orElseThrow());
        }
        if (journal.phase() == OperationPhase.COMPLETE) {
            journals.clear(journal);
            return;
        }
        if (journal.phase() == OperationPhase.COMMIT_WRITTEN) {
            journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        } else if (journal.phase() != OperationPhase.REF_PUBLISHED) {
            throw new IOException("Invalid interrupted Save phase: " + journal.phase());
        }
        journal = journals.advance(journal, OperationPhase.COMPLETE);
        journals.clear(journal);
    }
}
