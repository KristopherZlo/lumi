package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.LongSupplier;

/** Sweeps loaded entity chunks, then waits for the matching origin/index boundary. */
public final class DurableSavePreparation implements SavePreparation {
    private final WorldStateReader reader;
    private final EntityChunkDurabilityGate entities;
    private final MutationDurabilityTracker mutations;
    private final Predicate<HistoryKey> includes;
    private final LongSupplier nanoTime;

    public DurableSavePreparation(
            WorldStateReader reader,
            EntityChunkDurabilityGate entities,
            MutationDurabilityTracker mutations) {
        this(reader, entities, mutations, ignored -> true, System::nanoTime);
    }

    public DurableSavePreparation(
            WorldStateReader reader,
            EntityChunkDurabilityGate entities,
            MutationDurabilityTracker mutations,
            Predicate<HistoryKey> includes) {
        this(reader, entities, mutations, includes, System::nanoTime);
    }

    DurableSavePreparation(
            WorldStateReader reader,
            EntityChunkDurabilityGate entities,
            MutationDurabilityTracker mutations,
            LongSupplier nanoTime) {
        this(reader, entities, mutations, ignored -> true, nanoTime);
    }

    DurableSavePreparation(
            WorldStateReader reader,
            EntityChunkDurabilityGate entities,
            MutationDurabilityTracker mutations,
            Predicate<HistoryKey> includes,
            LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.includes = Objects.requireNonNull(includes, "includes");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public Session begin() {
        return new PreparationSession(entities.trackedKeys().stream()
                .filter(includes)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    }

    private final class PreparationSession implements Session {
        private final List<EntityChunkKey> keys;
        private int next;
        private MutationDurabilityTracker.DurabilityBoundary boundary;
        private boolean complete;

        private PreparationSession(List<EntityChunkKey> keys) {
            this.keys = keys;
        }

        @Override
        public boolean prepareUntil(long deadlineNanos) throws IOException {
            while (next < keys.size() && nanoTime.getAsLong() < deadlineNanos) {
                EntityChunkKey key = keys.get(next++);
                entities.observeCurrent(key, reader.read(key));
            }
            if (next != keys.size()) {
                return false;
            }
            if (boundary == null) {
                boundary = mutations.durabilityBoundary();
            }
            complete = mutations.isDurable(boundary);
            return complete;
        }

        @Override
        public WorkingIndexSnapshot finish() {
            if (!complete) {
                throw new IllegalStateException("Save preparation is not durable");
            }
            return boundary.working();
        }

        @Override
        public WorkingIndexSnapshot previewGenerations() {
            if (!complete) {
                throw new IllegalStateException("Save preparation is not durable");
            }
            return boundary.builder();
        }

        @Override
        public OperationProgress progress() {
            if (next < keys.size()) {
                return new OperationProgress(
                        "Save: checking loaded entities", next, keys.size());
            }
            if (boundary == null || boundary.working().generations().isEmpty()) {
                return OperationProgress.indeterminate(
                        "Save: establishing durable boundary");
            }
            return new OperationProgress(
                    "Save: waiting for pending writes",
                    mutations.durableKeyCount(boundary),
                    boundary.working().generations().size());
        }
    }
}
