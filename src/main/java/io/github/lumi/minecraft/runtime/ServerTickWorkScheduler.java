package io.github.lumi.minecraft.runtime;

import io.github.lumi.minecraft.operation.DimensionOperationCoordinator;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Shares one work deadline and rotates the first dimension without skipping housekeeping. */
final class ServerTickWorkScheduler<T> {
    private final LongSupplier nanoTime;
    private final long budgetNanos;
    private int first;

    ServerTickWorkScheduler() {
        this(System::nanoTime, DimensionOperationCoordinator.DEFAULT_TICK_WORK_NANOS);
    }

    ServerTickWorkScheduler(LongSupplier nanoTime, long budgetNanos) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (budgetNanos < 1 || budgetNanos > DimensionOperationCoordinator.MAX_TICK_WORK_NANOS) {
            throw new IllegalArgumentException("Server work budget must be between 1 ns and 50 ms");
        }
        this.budgetNanos = budgetNanos;
    }

    void tick(List<T> dimensions, TickWork<T> work) throws IOException {
        if (dimensions.isEmpty()) return;
        long start = nanoTime.getAsLong();
        long deadline = start > Long.MAX_VALUE - budgetNanos
                ? Long.MAX_VALUE : start + budgetNanos;
        int beginning = first % dimensions.size();
        first = (beginning + 1) % dimensions.size();
        IOException failure = null;
        for (int offset = 0; offset < dimensions.size(); offset++) {
            try {
                work.tick(dimensions.get((beginning + offset) % dimensions.size()), deadline);
            } catch (IOException dimensionFailure) {
                if (failure == null) failure = dimensionFailure;
                else failure.addSuppressed(dimensionFailure);
            }
        }
        if (failure != null) throw failure;
    }

    @FunctionalInterface
    interface TickWork<T> {
        void tick(T dimension, long deadlineNanos) throws IOException;
    }
}
