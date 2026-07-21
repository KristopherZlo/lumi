package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.ActiveWorkspace;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.storage.repository.ActiveBranchRepository;
import io.github.lumi.storage.repository.ActiveWorkspaceRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import java.io.IOException;
import java.util.Objects;

/** Recognizes publication that reached its atomic pointer before the crash. */
public final class PublishedApplyRecovery {
    private final BranchRefRepository refs;
    private final ActiveBranchRepository active;
    private final ActiveWorkspaceRepository activeWorkspace;
    private final WorkingIndexRepository working;
    private final OperationJournalRepository journals;

    public PublishedApplyRecovery(
            BranchRefRepository refs,
            ActiveBranchRepository active,
            OperationJournalRepository journals) {
        this(refs, active, null, journals);
    }

    public PublishedApplyRecovery(
            BranchRefRepository refs,
            ActiveBranchRepository active,
            ActiveWorkspaceRepository activeWorkspace,
            OperationJournalRepository journals) {
        this(refs, active, activeWorkspace, null, journals);
    }

    public PublishedApplyRecovery(
            BranchRefRepository refs,
            ActiveBranchRepository active,
            ActiveWorkspaceRepository activeWorkspace,
            WorkingIndexRepository working,
            OperationJournalRepository journals) {
        this.refs = Objects.requireNonNull(refs, "refs");
        this.active = Objects.requireNonNull(active, "active");
        this.activeWorkspace = activeWorkspace;
        this.working = working;
        this.journals = Objects.requireNonNull(journals, "journals");
    }

    public boolean finalizeIfPublished(OperationJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        if (!isPublished(journal)) {
            return false;
        }
        if (journal.capturedGenerations().isPresent()) {
            if (working == null) {
                return false;
            }
            working.clearCaptured(journal.capturedGenerations().orElseThrow());
        }
        if (journal.phase() != OperationPhase.REF_PUBLISHED
                && journal.phase() != OperationPhase.COMPLETE) {
            journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        }
        if (journal.phase() != OperationPhase.COMPLETE) {
            journal = journals.advance(journal, OperationPhase.COMPLETE);
        }
        journals.clear(journal);
        return true;
    }

    private boolean isPublished(OperationJournal journal) throws IOException {
        var target = journal.target();
        if (journal.kind() == OperationKind.SAVE
                || journal.kind() == OperationKind.QUICK_ROLLBACK
                || target.blockArea().isPresent()
                || target.target().isEmpty()) {
            return false;
        }
        if (journal.kind() == OperationKind.BRANCH_SWITCH) {
            var switchTarget = target.branchSwitch().orElseThrow(
                    () -> new IOException("Branch-switch journal target is missing"));
            BranchRef destination = refs.read(switchTarget.branch()).orElse(null);
            ActiveBranch selected = active.read().orElse(null);
            boolean branchPublished = destination != null
                    && destination.revision() == switchTarget.targetRevision()
                    && destination.commit().equals(target.target().orElseThrow())
                    && new ActiveBranch(
                            switchTarget.branch(),
                            Math.addExact(switchTarget.expectedActiveRevision(), 1))
                            .equals(selected);
            if (!branchPublished || target.workspaceSwitch().isEmpty()) {
                return branchPublished;
            }
            if (activeWorkspace == null) {
                return false;
            }
            var workspace = target.workspaceSwitch().orElseThrow();
            return new ActiveWorkspace(
                    workspace.targetWorkspace(),
                    Math.addExact(workspace.expectedRevision(), 1))
                    .equals(activeWorkspace.read().orElse(null));
        }
        BranchRef published = new BranchRef(
                target.branch(), target.target().orElseThrow(),
                Math.addExact(target.expectedRevision(), 1));
        return refs.read(target.branch()).filter(published::equals).isPresent();
    }
}
