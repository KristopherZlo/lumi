package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.DimensionFreeze;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Serializes mutation and enforces the global 50 ms server-tick work limit. */
public final class DimensionOperationCoordinator implements AutoCloseable {
    public static final long MAX_TICK_WORK_NANOS = 50_000_000L;
    public static final int MAX_QUEUED_OPERATIONS = 64;

    private final DimensionFreeze freeze;
    private final LongSupplier nanoTime;
    private final long tickBudgetNanos;
    private final Consumer<DimensionMutation> terminalObserver;
    private final Consumer<RuntimeException> observerFailure;
    private final ArrayList<QueuedOperation> queued = new ArrayList<>();
    private final HashMap<OperationTicket, IntConsumer> positionObservers = new HashMap<>();
    private final HashMap<OperationTicket, Consumer<OperationProgress>> progressObservers =
            new HashMap<>();
    private final HashMap<OperationTicket, Runnable> freezeObservers = new HashMap<>();
    private final HashMap<OperationTicket, OperationProgress> publishedProgress = new HashMap<>();
    private FailureContainedMutation active;
    private OperationTicket activeTicket;
    private Consumer<DimensionMutation> activeObserver = ignored -> { };
    private DimensionFreeze.Lease lease;
    private boolean freezeReleased;
    private boolean freezeStartReported;
    private boolean terminalReported;

    public DimensionOperationCoordinator(DimensionFreeze freeze) {
        this(freeze, ignored -> { });
    }

    public DimensionOperationCoordinator(
            DimensionFreeze freeze, Consumer<DimensionMutation> terminalObserver) {
        this(freeze, terminalObserver, ignored -> { });
    }

    public DimensionOperationCoordinator(
            DimensionFreeze freeze,
            Consumer<DimensionMutation> terminalObserver,
            Consumer<RuntimeException> observerFailure) {
        this(freeze, System::nanoTime, MAX_TICK_WORK_NANOS,
                terminalObserver, observerFailure);
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze, LongSupplier nanoTime, long tickBudgetNanos) {
        this(freeze, nanoTime, tickBudgetNanos, ignored -> { }, ignored -> { });
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze,
            LongSupplier nanoTime,
            long tickBudgetNanos,
            Consumer<DimensionMutation> terminalObserver) {
        this(freeze, nanoTime, tickBudgetNanos, terminalObserver, ignored -> { });
    }

    DimensionOperationCoordinator(
            DimensionFreeze freeze,
            LongSupplier nanoTime,
            long tickBudgetNanos,
            Consumer<DimensionMutation> terminalObserver,
            Consumer<RuntimeException> observerFailure) {
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
        this.observerFailure = Objects.requireNonNull(observerFailure, "observerFailure");
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
        enqueue(operation, OperationPriority.NORMAL, operationObserver);
    }

    public synchronized OperationTicket enqueue(
            DimensionMutation operation,
            OperationPriority priority,
            Consumer<DimensionMutation> operationObserver) {
        var entry = new QueuedOperation(
                new OperationTicket(UUID.randomUUID()),
                Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(priority, "priority"),
                Objects.requireNonNull(operationObserver, "operationObserver"));
        if (active == null && queued.isEmpty()) {
            activate(entry);
            return entry.ticket();
        }
        if (queued.size() >= MAX_QUEUED_OPERATIONS) {
            throw new IllegalStateException("Dimension operation queue is full");
        }
        int position = 0;
        while (position < queued.size()
                && queued.get(position).priority().ordinal() <= priority.ordinal()) {
            position++;
        }
        queued.add(position, entry);
        notifyPositions();
        return entry.ticket();
    }

    private void activate(QueuedOperation entry) {
        active = new FailureContainedMutation(entry.operation());
        activeTicket = entry.ticket();
        activeObserver = entry.observer();
        freezeReleased = false;
        freezeStartReported = false;
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
        if (active != null || !queued.isEmpty()) {
            throw new IllegalStateException("Recovery requires an empty operation queue");
        }
        activate(new QueuedOperation(
                new OperationTicket(UUID.randomUUID()), operation,
                OperationPriority.URGENT,
                Objects.requireNonNull(operationObserver, "operationObserver")));
        lease = existingLease;
    }

    public synchronized void tick() throws IOException {
        if (active == null) {
            activateNext();
        }
        if (active == null) {
            return;
        }
        if (active.isTerminal()) {
            notifyProgress();
            reportTerminal();
            releaseFreezeIfSafe();
            clearTerminalIfSafe();
            return;
        }
        if (lease == null && !freezeReleased && active.requiresFreeze()) {
            lease = Objects.requireNonNull(freeze.acquire(), "freeze lease");
        }
        if (lease != null && !freezeStartReported) {
            reportFreezeStart();
        }
        long start = nanoTime.getAsLong();
        long deadline = start > Long.MAX_VALUE - tickBudgetNanos
                ? Long.MAX_VALUE : start + tickBudgetNanos;
        active.advance(deadline);
        notifyProgress();
        reportTerminal();
        releaseFreezeIfSafe();
        clearTerminalIfSafe();
    }

    private void releaseFreezeIfSafe() {
        if (lease != null && active.isSafeToRelease()) {
            lease.release();
            lease = null;
            freezeReleased = true;
        }
    }

    private void clearTerminalIfSafe() {
        if (active.isTerminal() && active.isSafeToRelease()) {
            positionObservers.remove(activeTicket);
            progressObservers.remove(activeTicket);
            freezeObservers.remove(activeTicket);
            publishedProgress.remove(activeTicket);
            active = null;
            activeTicket = null;
            activeObserver = ignored -> { };
            freezeReleased = false;
            freezeStartReported = false;
            terminalReported = false;
            activateNext();
        }
    }

    private void activateNext() {
        if (active == null && !queued.isEmpty()) {
            activate(queued.removeFirst());
            notifyPositions();
            notifyProgress();
        }
    }

    private void reportTerminal() {
        if (active.isTerminal() && !terminalReported) {
            DimensionMutation outcome = active.outcome();
            terminalReported = true;
            notifyObserver(terminalObserver, outcome, "global terminal");
            notifyObserver(activeObserver, outcome, "request terminal");
        }
    }

    private void notifyObserver(
            Consumer<DimensionMutation> observer,
            DimensionMutation outcome,
            String description) {
        try {
            observer.accept(outcome);
        } catch (RuntimeException failed) {
            reportObserverFailure(description, failed);
        }
    }

    private void reportObserverFailure(String description, RuntimeException failed) {
        try {
            observerFailure.accept(new IllegalStateException(
                    "Lumi " + description + " observer failed", failed));
        } catch (RuntimeException ignored) {
            // Diagnostics must never interrupt freeze release or operation ownership.
        }
    }

    public synchronized boolean hasActiveOperation() {
        return active != null;
    }

    public synchronized int queuedCount() {
        return queued.size();
    }

    public synchronized OptionalInt queuePosition(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.equals(activeTicket)) {
            return OptionalInt.of(0);
        }
        for (int index = 0; index < queued.size(); index++) {
            if (queued.get(index).ticket().equals(ticket)) {
                return OptionalInt.of(index + 1);
            }
        }
        return OptionalInt.empty();
    }

    public synchronized Optional<OperationTicket> ticketOf(DimensionMutation operation) {
        Objects.requireNonNull(operation, "operation");
        if (active != null && active.source() == operation) {
            return Optional.of(activeTicket);
        }
        return queued.stream().filter(entry -> entry.operation() == operation)
                .map(QueuedOperation::ticket).findFirst();
    }

    public synchronized boolean cancel(OperationTicket ticket) throws IOException {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.equals(activeTicket)) {
            if (!active.cancel()) {
                return false;
            }
            if (!active.isTerminal() || !active.isSafeToRelease()
                    || active.terminalState() != MutationTerminalState.CANCELLED) {
                throw new IOException(
                        "Operation accepted cancellation without a safe CANCELLED state");
            }
            notifyProgress();
            reportTerminal();
            releaseFreezeIfSafe();
            clearTerminalIfSafe();
            return true;
        }
        for (int index = 0; index < queued.size(); index++) {
            QueuedOperation entry = queued.get(index);
            if (!entry.ticket().equals(ticket)) {
                continue;
            }
            entry.operation().close();
            queued.remove(index);
            positionObservers.remove(ticket);
            progressObservers.remove(ticket);
            freezeObservers.remove(ticket);
            publishedProgress.remove(ticket);
            notifyPositions();
            return true;
        }
        return false;
    }

    public synchronized void observeQueuePosition(
            OperationTicket ticket, IntConsumer observer) {
        int position = queuePosition(Objects.requireNonNull(ticket, "ticket"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown operation ticket"));
        positionObservers.put(ticket, Objects.requireNonNull(observer, "observer"));
        try {
            observer.accept(position);
        } catch (RuntimeException failed) {
            positionObservers.remove(ticket);
            reportObserverFailure("queue position", failed);
        }
    }

    public synchronized void observeProgress(
            OperationTicket ticket, Consumer<OperationProgress> observer) {
        OperationProgress progress = progress(ticket);
        progressObservers.put(ticket, Objects.requireNonNull(observer, "observer"));
        publishedProgress.put(ticket, progress);
        try {
            observer.accept(progress);
        } catch (RuntimeException failed) {
            progressObservers.remove(ticket);
            publishedProgress.remove(ticket);
            reportObserverFailure("progress", failed);
        }
    }

    /** Runs once after the dimension is frozen and before the first mutation step. */
    public synchronized void observeFreezeAcquired(
            OperationTicket ticket, Runnable observer) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(observer, "observer");
        if (ticket.equals(activeTicket) && freezeStartReported) {
            throw new IllegalStateException("Operation freeze boundary has already passed");
        }
        if (queuePosition(ticket).isEmpty()) {
            throw new IllegalArgumentException("Unknown operation ticket");
        }
        freezeObservers.put(ticket, observer);
    }

    private void reportFreezeStart() {
        freezeStartReported = true;
        Runnable observer = freezeObservers.remove(activeTicket);
        if (observer == null) {
            return;
        }
        try {
            observer.run();
        } catch (RuntimeException failed) {
            reportObserverFailure("freeze boundary", failed);
        }
    }

    private OperationProgress progress(OperationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (ticket.equals(activeTicket)) {
            return active.progress();
        }
        if (queued.stream().anyMatch(entry -> entry.ticket().equals(ticket))) {
            return OperationProgress.indeterminate("Queued");
        }
        throw new IllegalArgumentException("Unknown operation ticket");
    }

    private void notifyProgress() {
        progressObservers.entrySet().removeIf(entry -> {
            OperationProgress progress;
            try {
                progress = progress(entry.getKey());
            } catch (IllegalArgumentException unknown) {
                publishedProgress.remove(entry.getKey());
                return true;
            }
            if (!progress.equals(publishedProgress.put(entry.getKey(), progress))) {
                try {
                    entry.getValue().accept(progress);
                } catch (RuntimeException failed) {
                    publishedProgress.remove(entry.getKey());
                    reportObserverFailure("progress", failed);
                    return true;
                }
            }
            return false;
        });
    }

    private void notifyPositions() {
        positionObservers.entrySet().removeIf(entry -> {
            OptionalInt position = queuePosition(entry.getKey());
            if (position.isEmpty()) {
                return true;
            }
            try {
                entry.getValue().accept(position.orElseThrow());
            } catch (RuntimeException failed) {
                reportObserverFailure("queue position", failed);
                return true;
            }
            return false;
        });
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (QueuedOperation entry : queued) {
            try {
                entry.operation().close();
            } catch (IOException failed) {
                failure = append(failure, failed);
            }
        }
        if (active != null) {
            try {
                active.close();
            } catch (IOException failed) {
                failure = append(failure, failed);
            }
        }
        if (lease != null) {
            lease.release();
        }
        queued.clear();
        positionObservers.clear();
        progressObservers.clear();
        freezeObservers.clear();
        publishedProgress.clear();
        active = null;
        activeTicket = null;
        lease = null;
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException append(IOException failure, IOException added) {
        if (failure == null) {
            return added;
        }
        failure.addSuppressed(added);
        return failure;
    }

    private record QueuedOperation(
            OperationTicket ticket,
            DimensionMutation operation,
            OperationPriority priority,
            Consumer<DimensionMutation> observer) { }
}
