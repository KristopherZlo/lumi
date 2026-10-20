package io.github.lumi.domain.service;

import io.github.lumi.domain.model.*;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** Expands an interrupted native intent into the existing world-recovery protocol. */
public final class NativeActionRecovery {
    private final WorldActionHistory history;
    private final ActionReplayPreparation preparation;
    private final OperationJournalRepository journals;

    public NativeActionRecovery(WorldActionHistory history, ActionReplayPreparation preparation, OperationJournalRepository journals) {
        this.history = history;
        this.preparation = preparation;
        this.journals = journals;
    }

    public Optional<OperationJournal> open(BranchRef expected, java.util.UUID workspace) throws IOException {
        var intent = history.pendingNativeReplay();
        var existing = journals.read();
        if (intent.isEmpty()) return existing;
        var state = intent.orElseThrow();
        var marker = state.publication().orElseThrow();
        if (!state.workspace().equals(workspace) || !state.branch().equals(expected.name())) throw new IOException("Native replay belongs to another workspace");
        if (existing.isPresent()) {
            if (!existing.orElseThrow().operationId().equals(marker.operation())) throw new IOException("Native replay conflicts with another operation");
            return existing;
        }
        var plan = history.nativeReplayPlan(state);
        if (plan.sections().isEmpty() || !plan.expected().isEmpty() || !plan.expectedEntities().isEmpty()
                || !plan.biomes().isEmpty() || plan.checkpoint().isPresent()) throw new IOException("Invalid native buffer recovery payload");
        var before = plan.expectedSections();
        var replay = preparation.prepareSections(expected, before, Map.of(), plan, new CommitAuthor(plan.player(), "Builder"));
        var xs = before.keySet().stream().mapToInt(SectionKey::chunkX).summaryStatistics();
        var ys = before.keySet().stream().mapToInt(SectionKey::sectionY).summaryStatistics();
        var zs = before.keySet().stream().mapToInt(SectionKey::chunkZ).summaryStatistics();
        // A block scope also prevents recovery from changing player respawns or unrelated entities.
        var area = new BlockBox(xs.getMin() * 16, ys.getMin() * 16, zs.getMin() * 16,
                xs.getMax() * 16 + 15, ys.getMax() * 16 + 15, zs.getMax() * 16 + 15);
        var target = new OperationTarget(expected.name(), expected.commit(), expected.revision(),
                Optional.of(replay.restore().targetCommit()), Optional.of(replay.returnPoint()), Optional.empty(),
                Optional.of(new BlockAreaTarget(area, false)));
        return Optional.of(journals.create(new OperationJournal(marker.operation(), OperationKind.ACTION_REPLAY,
                OperationPhase.APPLYING, target, Optional.empty(), Optional.of(marker.step()))));
    }
}
