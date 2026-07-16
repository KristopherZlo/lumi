package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serializes mutation and enforces the global 50 ms server-tick work limit. */
public final class DimensionOperationCoordinator {
    public static final long MAX_TICK_WORK_NANOS = 50_000_000L;

    private final DimensionFreeze freeze;
    private final LongSupplier nanoTime;
    private final long tickBudgetNanos;
    private final Consumer<DimensionMutation> terminalObserver;
    private DimensionMutation active;
    private Consumer<DimensionMutation> activeObserver = ignored -> { };
    private DimensionFreeze.Lease lease;
    private boolean freezeReleased;
    private boolean terminalReported;

    public DimensionOperationCoordinator(DimensionFreeze freeze) {
        this(freeze, ignored -> { });
    }

    public DimensionOperationCoordinator(
            DimensionFreeze freeze, Consumer<DimensionMutation> terminalObserver) {
        this(freeze, System::nanoTime, MAX_TICK_WORK_NANOS, terminalObserver);
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze, LongSupplier nanoTime, long tickBudgetNanos) {
        this(freeze, nanoTime, tickBudgetNanos, ignored -> { });
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze,
            LongSupplier nanoTime,
            long tickBudgetNanos,
            Consumer<DimensionMutation> terminalObserver) {
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
        if (tickBudgetNanos < 1 || tickBudgetNanos > MAX_TICK_WORK_NANOS) {
            throw new IllegalArgumentException("Tick budget must be between 1 ns and 50 ms");
        }
        this.tickBudgetNanos = tickBudgetNanos;
    }

    public synchronized void start(DimensionMutation operation) {
        start(operation, ignored -> { });
    }

    public synchronized void start(
            DimensionMutation operation, Consumer<DimensionMutation> operationObserver) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(operationObserver, "operationObserver");
        if (active != null) {
            throw new IllegalStateException("A dimension mutation is already active");
        }
        active = operation;
        activeObserver = operationObserver;
        freezeReleased = false;
        terminalReported = false;
    }

    /** Transfers an already-held startup recovery freeze without releasing the dimension. */
    public synchronized void startWithLease(
            DimensionMutation operation, DimensionFreeze.Lease existingLease) {
        startWithLease(operation, existingLease, ignored -> { });
    }

    public synchronized void startWithLease(
            DimensionMutation operation,
            DimensionFreeze.Lease existingLease,
            Consumer<DimensionMutation> operationObserver) {
        Objects.requireNonNull(existingLease, "existingLease");
        if (!Objects.requireNonNull(operation, "operation").requiresFreeze()) {
            throw new IllegalArgumentException("A recovery operation must retain the dimension freeze");
        }
        start(operation, operationObserver);
        lease = existingLease;
    }

    public synchronized void tick() throws IOException {
        if (active == null) {
            return;
        }
        if (lease == null && !freezeReleased && active.requiresFreeze()) {
            lease = Objects.requireNonNull(freeze.acquire(), "freeze lease");
        }
        if (active.isTerminal()) {
            reportTerminal();
            return;
        }
        long start = nanoTime.getAsLong();
        long deadline = start > Long.MAX_VALUE - tickBudgetNanos
                ? Long.MAX_VALUE : start + tickBudgetNanos;
        active.advance(deadline);
        reportTerminal();
        if (lease != null && active.isSafeToRelease()) {
            lease.release();
            lease = null;
            freezeReleased = true;
        }
        if (active.isTerminal() && active.isSafeToRelease()) {
            active = null;
            activeObserver = ignored -> { };
            freezeReleased = false;
            terminalReported = false;
        }
    }

    private void reportTerminal() {
        if (active.isTerminal() && !terminalReported) {
            terminalObserver.accept(active);
            activeObserver.accept(active);
            terminalReported = true;
        }
    }

    public synchronized boolean hasActiveOperation() {
        return active != null;
    }
}
