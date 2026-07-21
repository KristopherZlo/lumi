package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.LiveBlockWorldAccess;
import io.github.lumi.minecraft.world.LiveEntityWorldAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Frozen, deadline-bounded and exactly verified live Undo/Redo operation. */
public final class LiveActionOperation implements DimensionMutation {
    private final LiveActionJournal journal;
    private final UUID player;
    private final LiveActionJournal.Direction direction;
    private final LiveBlockWorldAccess world;
    private final LiveEntityWorldAccess entities;
    private final LongSupplier nanoTime;
    private final PendingCancellation cancelPending;
    private final Consumer<LiveActionJournal.Plan> publication;
    private final List<WorldChange<?>> mismatches = new ArrayList<>();
    private List<WorldChange<?>> changes = List.of();
    private Phase phase = Phase.SELECTING;

    @Override public OperationProgress progress() {
        return OperationProgress.indeterminate("Live action: " + phase.name().toLowerCase());
    }
    private LiveActionJournal.Plan plan;
    private Iterator<WorldChange<?>> cursor;
    private Throwable failure;
    private UUID retainedAction;
    private boolean cancellationChangedState;

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world) {
        this(journal, player, direction, world, LiveEntityWorldAccess.UNSUPPORTED,
                System::nanoTime, ignored -> false, ignored -> { });
    }

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            Consumer<UUID> cancelPending) {
        this(journal, player, direction, world, LiveEntityWorldAccess.UNSUPPORTED,
                System::nanoTime, adapt(cancelPending), ignored -> { });
    }

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LiveEntityWorldAccess entities,
            Consumer<UUID> cancelPending) {
        this(journal, player, direction, world, entities,
                System::nanoTime, adapt(cancelPending), ignored -> { });
    }

    public LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LiveEntityWorldAccess entities,
            PendingCancellation cancelPending,
            Consumer<LiveActionJournal.Plan> publication) {
        this(journal, player, direction, world, entities,
                System::nanoTime, cancelPending, publication);
    }

    LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LongSupplier nanoTime) {
        this(journal, player, direction, world, LiveEntityWorldAccess.UNSUPPORTED,
                nanoTime, ignored -> false, ignored -> { });
    }

    LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LongSupplier nanoTime,
            Consumer<UUID> cancelPending) {
        this(journal, player, direction, world, LiveEntityWorldAccess.UNSUPPORTED,
                nanoTime, adapt(cancelPending), ignored -> { });
    }

    private LiveActionOperation(
            LiveActionJournal journal,
            UUID player,
            LiveActionJournal.Direction direction,
            LiveBlockWorldAccess world,
            LiveEntityWorldAccess entities,
            LongSupplier nanoTime,
            PendingCancellation cancelPending,
            Consumer<LiveActionJournal.Plan> publication) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.player = Objects.requireNonNull(player, "player");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.world = Objects.requireNonNull(world, "world");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.cancelPending = Objects.requireNonNull(cancelPending, "cancelPending");
        this.publication = Objects.requireNonNull(publication, "publication");
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
                phase = beforeMutation() ? Phase.FAILED : Phase.DEGRADED;
            }
        }
        if (isTerminal() && retainedAction != null) {
            journal.release(retainedAction);
            retainedAction = null;
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
        Optional<LiveActionJournal.Plan> selected = selectPlan();
        if (selected.isEmpty()) {
            phase = Phase.FAILED;
            return;
        }
        plan = selected.orElseThrow();
        phase = Phase.CANCELLING;
    }

    private void cancelPending() {
        retainedAction = plan.actionId();
        journal.retain(retainedAction);
        cancellationChangedState = cancelPending.cancel(plan.actionId());
        plan = selectPlan().orElseThrow(
                () -> new IllegalStateException("Live action disappeared during finalization"));
        prepareChanges();
        cursor = changes.iterator();
        phase = Phase.VALIDATING;
    }

    private void prepareChanges() {
        var prepared = new ArrayList<WorldChange<?>>(
                plan.expected().size() + plan.expectedEntities().size());
        plan.expected().forEach((position, expected) -> prepared.add(new BlockChange(
                world, position, expected, plan.replacement().get(position))));
        plan.expectedEntities().forEach((id, expected) -> prepared.add(new EntityChange(
                entities, id, expected, plan.replacementEntities().get(id))));
        changes = List.copyOf(prepared);
    }

    private Optional<LiveActionJournal.Plan> selectPlan() {
        return direction == LiveActionJournal.Direction.UNDO
                ? journal.prepareUndo(player) : journal.prepareRedo(player);
    }

    private void validateOne() throws IOException {
        if (!cursor.hasNext()) {
            cursor = changes.iterator();
            phase = Phase.APPLYING;
            return;
        }
        WorldChange<?> change = cursor.next();
        change.requireReplacementPrepared();
        if (!change.matchesExpected()) {
            failure = new IllegalStateException(
                    "Visible world conflicts with live action at " + change.conflict());
            phase = cancellationChangedState ? Phase.DEGRADED : Phase.FAILED;
        }
    }

    private void applyOne() throws IOException {
        if (!cursor.hasNext()) {
            cursor = changes.iterator();
            mismatches.clear();
            phase = Phase.VERIFYING;
            return;
        }
        cursor.next().applyReplacement();
    }

    private void verifyOne(boolean finalPass) throws IOException {
        if (!cursor.hasNext()) {
            if (mismatches.isEmpty()) {
                publication.accept(plan);
                journal.complete(plan);
                phase = Phase.SUCCEEDED;
            } else if (finalPass) {
                failure = new IllegalStateException(
                        "Live action did not verify after one repair pass at "
                                + mismatches.getFirst().mismatch());
                phase = Phase.DEGRADED;
            } else {
                cursor = mismatches.iterator();
                phase = Phase.REPAIRING;
            }
            return;
        }
        WorldChange<?> change = cursor.next();
        if (!change.matchesReplacement()) {
            mismatches.add(change);
        }
    }

    private void repairOne() throws IOException {
        if (!cursor.hasNext()) {
            mismatches.clear();
            cursor = changes.iterator();
            phase = Phase.REVERIFYING;
            return;
        }
        cursor.next().applyReplacement();
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

    private boolean beforeMutation() {
        return phase == Phase.SELECTING
                || (phase == Phase.VALIDATING && !cancellationChangedState);
    }

    private static PendingCancellation adapt(Consumer<UUID> cancellation) {
        Objects.requireNonNull(cancellation, "cancelPending");
        return action -> {
            cancellation.accept(action);
            return false;
        };
    }

    @FunctionalInterface
    public interface PendingCancellation {
        boolean cancel(UUID action);
    }

    private abstract static class WorldChange<S> {
        private final S expected;
        private final S replacement;
        private S actual;

        private WorldChange(S expected, S replacement) {
            this.expected = Objects.requireNonNull(expected, "expected");
            this.replacement = Objects.requireNonNull(replacement, "replacement");
        }

        private boolean matchesExpected() throws IOException {
            actual = read();
            return expected.equals(actual);
        }

        private void requireReplacementPrepared() throws IOException {
            requirePrepared(replacement);
        }

        private boolean matchesReplacement() throws IOException {
            actual = read();
            return replacement.equals(actual);
        }

        private void applyReplacement() throws IOException {
            write(replacement);
        }

        private String mismatch() {
            return target() + "; expected=" + replacement + "; actual=" + actual;
        }

        private String conflict() {
            return target() + "; expected=" + expected + "; actual=" + actual;
        }

        protected abstract S read() throws IOException;

        protected abstract void requirePrepared(S state) throws IOException;

        protected abstract void write(S state) throws IOException;

        protected abstract String target();
    }

    private static final class BlockChange extends WorldChange<BlockSnapshot> {
        private final LiveBlockWorldAccess world;
        private final BlockPosition position;

        private BlockChange(
                LiveBlockWorldAccess world,
                BlockPosition position,
                BlockSnapshot expected,
                BlockSnapshot replacement) {
            super(expected, replacement);
            this.world = world;
            this.position = position;
        }

        @Override protected BlockSnapshot read() throws IOException { return world.read(position); }
        @Override protected void requirePrepared(BlockSnapshot state) throws IOException {
            world.requirePrepared(state);
        }
        @Override protected void write(BlockSnapshot state) throws IOException { world.write(position, state); }
        @Override protected String target() { return "block " + position; }
    }

    private static final class EntityChange extends WorldChange<Optional<EntityState>> {
        private final LiveEntityWorldAccess world;
        private final UUID entityId;

        private EntityChange(
                LiveEntityWorldAccess world,
                UUID entityId,
                Optional<EntityState> expected,
                Optional<EntityState> replacement) {
            super(expected, replacement);
            this.world = world;
            this.entityId = entityId;
        }

        @Override protected Optional<EntityState> read() throws IOException { return world.read(entityId); }
        @Override protected void requirePrepared(Optional<EntityState> state) throws IOException {
            world.requirePrepared(state);
        }
        @Override protected void write(Optional<EntityState> state) throws IOException { world.write(entityId, state); }
        @Override protected String target() { return "entity " + entityId; }
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
        DEGRADED
    }
}
