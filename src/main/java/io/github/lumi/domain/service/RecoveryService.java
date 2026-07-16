package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import java.io.IOException;
import java.util.Objects;

/** Reconstructs either safe direction from immutable crash-journal targets. */
public final class RecoveryService {
    private final RestoreService restores;

    public RecoveryService(RestoreService restores) {
        this.restores = Objects.requireNonNull(restores, "restores");
    }

    public PreparedRestore prepare(OperationJournal journal, RecoveryChoice choice)
            throws IOException {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(choice, "choice");
        if (journal.kind() == OperationKind.SAVE) {
            throw new IOException("Interrupted Save has no world apply to recover");
        }
        var target = journal.target();
        CommitId operationTarget = target.target().orElseThrow(
                () -> new IOException("Recovery journal has no target commit"));
        CommitId checkpoint = target.returnPoint().orElse(target.expectedHead());
        CommitId source = choice == RecoveryChoice.RESUME_TARGET
                ? checkpoint : operationTarget;
        CommitId desired = choice == RecoveryChoice.RESUME_TARGET
                ? operationTarget : checkpoint;
        BranchRef expected = new BranchRef(
                target.branch(), target.expectedHead(), target.expectedRevision());
        if (target.blockArea().isPresent()) {
            var area = target.blockArea().orElseThrow();
            return restores.preparePartial(
                    expected, source, desired, area.area(), area.outside());
        }
        if (target.excludeEntities()) {
            return restores.prepareWithoutEntities(expected, source, desired);
        }
        return restores.prepare(expected, source, desired);
    }
}
