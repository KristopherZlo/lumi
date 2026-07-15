package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Serializes mutation and enforces the global 50 ms server-tick work limit. */
public final class DimensionOperationCoordinator {
    public static final long MAX_TICK_WORK_NANOS = 50_000_000L;

    private final DimensionFreeze freeze;
    private final LongSupplier nanoTime;
    private final long tickBudgetNanos;
    private DimensionMutation active;
    private DimensionFreeze.Lease lease;

    public DimensionOperationCoordinator(DimensionFreeze freeze) {
        this(freeze, System::nanoTime, MAX_TICK_WORK_NANOS);
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze, LongSupplier nanoTime, long tickBudgetNanos) {
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (tickBudgetNanos < 1 || tickBudgetNanos > MAX_TICK_WORK_NANOS) {
            throw new IllegalArgumentException("Tick budget must be between 1 ns and 50 ms");
        }
        this.tickBudgetNanos = tickBudgetNanos;
    }

    public synchronized void start(DimensionMutation operation) {
        Objects.requireNonNull(operation, "operation");
        if (active != null) {
            throw new IllegalStateException("A dimension mutation is already active");
        }
        active = operation;
    }

    public synchronized void tick() throws IOException {
        if (active == null) {
            return;
        }
        if (lease == null) {
            lease = Objects.requireNonNull(freeze.acquire(), "freeze lease");
        }
        long start = nanoTime.getAsLong();
        long deadline = start > Long.MAX_VALUE - tickBudgetNanos
                ? Long.MAX_VALUE : start + tickBudgetNanos;
        active.advance(deadline);
        if (active.isTerminal() && active.isSafeToRelease()) {
            lease.release();
            lease = null;
            active = null;
        }
    }

    public synchronized boolean hasActiveOperation() {
        return active != null;
    }
}
