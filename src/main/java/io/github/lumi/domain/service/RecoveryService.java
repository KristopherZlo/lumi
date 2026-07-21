package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Reconstructs either safe direction from immutable crash-journal targets. */
public final class RecoveryService {
    private final RestoreService restores;
    private final ZoneService zones;

    public RecoveryService(RestoreService restores) {
        this(restores, null);
    }

    public RecoveryService(RestoreService restores, ZoneService zones) {
        this.restores = Objects.requireNonNull(restores, "restores");
        this.zones = zones;
    }

    public PreparedRestore prepare(OperationJournal journal, RecoveryChoice choice)
            throws IOException {
        return prepare(journal, choice, ignored -> { });
    }

    public PreparedRestore prepare(
            OperationJournal journal,
            RecoveryChoice choice,
            Consumer<RestoreService.PreparationProgress> progress) throws IOException {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(progress, "progress");
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
                    expected, source, desired, area.area(), area.outside(), progress);
        }
        if (target.zoneRestore().isPresent()) {
            if (zones == null) {
                throw new IOException("Zone recovery service is unavailable");
            }
            var zoneTarget = target.zoneRestore().orElseThrow();
            var zone = zones.require(zoneTarget.workspaceId(), zoneTarget.zoneId());
            if (zone.revision() != zoneTarget.revision()) {
                throw new IOException("Zone changed since Restore started: " + zone.id());
            }
            return restores.prepareZone(expected, source, desired, zone, progress);
        }
        if (target.excludeEntities()) {
            return restores.prepareWithoutEntities(expected, source, desired, progress);
        }
        return restores.prepare(expected, source, desired, progress);
    }
}
