package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.LiveBlockWorldAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Frozen, deadline-bounded and exactly verified live Undo/Redo operation. */
public final class LiveActionOperation implements DimensionMutation {
    private final LiveActionJournal journal;
    private final UUID player;
    private final LiveActionJournal.Direction direction;
    private final LiveBlockWorldAccess world;
    private final LongSupplier nanoTime;
    private final Consumer<UUID> cancelPending;
    private final List<BlockPosition> mismatches = new ArrayList<>();
    private Phase phase = Phase.SELECTING;
    private LiveActionJournal.Plan plan;
    private Iterator<Map.Entry<BlockPosition, BlockSnapshot>> cursor;
    private Throwable failure;

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world) {
        this(journal, player, direction, world, System::nanoTime);
    }

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            Consumer<UUID> cancelPending) {
        this(journal, player, direction, world, System::nanoTime, cancelPending);
    }

    LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LongSupplier nanoTime) {
        this(journal, player, direction, world, nanoTime, ignored -> { });
    }

    LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LongSupplier nanoTime,
            Consumer<UUID> cancelPending) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.player = Objects.requireNonNull(player, "player");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.world = Objects.requireNonNull(world, "world");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.cancelPending = Objects.requireNonNull(cancelPending, "cancelPending");
    }

    @Override
    public void advance(long deadlineNanos) {
        boolean first = true;
        while (!isTerminal() && (first || nanoTime.getAsLong() < deadlineNanos)) {
            first = false;
            try {
                step();
            } catch (IOException | RuntimeException exception) {
                failure = exception;
                phase = phase.beforeMutation() ? Phase.FAILED : Phase.DEGRADED;
            }
        }
    }

    private void step() throws IOException {
        switch (phase) {
            case SELECTING -> select();
            case CANCELLING -> cancelPending();
            case VALIDATING -> validateOne();
            case APPLYING -> applyOne();
            case VERIFYING -> verifyOne(false);
            case REPAIRING -> repairOne();
            case REVERIFYING -> verifyOne(true);
            case SUCCEEDED, FAILED, DEGRADED -> { }
        }
    }

    private void select() {
        Optional<LiveActionJournal.Plan> selected = direction == LiveActionJournal.Direction.UNDO
                ? journal.prepareUndo(player) : journal.prepareRedo(player);
        if (selected.isEmpty()) {
            phase = Phase.FAILED;
            return;
        }
        plan = selected.orElseThrow();
        phase = Phase.CANCELLING;
    }

    private void cancelPending() {
        if (direction == LiveActionJournal.Direction.UNDO) {
            cancelPending.accept(plan.actionId());
        }
        cursor = plan.expected().entrySet().iterator();
        phase = Phase.VALIDATING;
    }

    private void validateOne() throws IOException {
        if (!cursor.hasNext()) {
            cursor = plan.replacement().entrySet().iterator();
            phase = Phase.APPLYING;
            return;
        }
        var entry = cursor.next();
        if (!entry.getValue().equals(world.read(entry.getKey()))) {
            failure = new IllegalStateException("Visible world conflicts with live action at " + entry.getKey());
            phase = Phase.FAILED;
        }
    }

    private void applyOne() throws IOException {
        if (!cursor.hasNext()) {
            cursor = plan.replacement().entrySet().iterator();
            mismatches.clear();
            phase = Phase.VERIFYING;
            return;
        }
        var entry = cursor.next();
        world.write(entry.getKey(), entry.getValue());
    }

    private void verifyOne(boolean finalPass) throws IOException {
        if (!cursor.hasNext()) {
            if (mismatches.isEmpty()) {
                journal.complete(plan);
                phase = Phase.SUCCEEDED;
            } else if (finalPass) {
                failure = new IllegalStateException("Live action did not verify after one repair pass");
                phase = Phase.DEGRADED;
            } else {
                cursor = mismatches.stream()
                        .map(position -> Map.entry(position, plan.replacement().get(position)))
                        .iterator();
                phase = Phase.REPAIRING;
            }
            return;
        }
        var entry = cursor.next();
        if (!entry.getValue().equals(world.read(entry.getKey()))) {
            mismatches.add(entry.getKey());
        }
    }

    private void repairOne() throws IOException {
        if (!cursor.hasNext()) {
            mismatches.clear();
            cursor = plan.replacement().entrySet().iterator();
            phase = Phase.REVERIFYING;
            return;
        }
        var entry = cursor.next();
        world.write(entry.getKey(), entry.getValue());
    }

    @Override
    public boolean isTerminal() {
        return phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.DEGRADED;
    }

    @Override
    public boolean isSafeToRelease() {
        return phase == Phase.SUCCEEDED || phase == Phase.FAILED;
    }

    @Override
    public MutationTerminalState terminalState() {
        return switch (phase) {
            case SUCCEEDED -> MutationTerminalState.SUCCEEDED;
            case FAILED -> MutationTerminalState.FAILED;
            case DEGRADED -> MutationTerminalState.DEGRADED;
            default -> throw new IllegalStateException("Mutation is not terminal");
        };
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    private enum Phase {
        SELECTING,
        CANCELLING,
        VALIDATING,
        APPLYING,
        VERIFYING,
        REPAIRING,
        REVERIFYING,
        SUCCEEDED,
        FAILED,
        DEGRADED;

        private boolean beforeMutation() {
            return this == SELECTING || this == VALIDATING;
        }
    }
}
