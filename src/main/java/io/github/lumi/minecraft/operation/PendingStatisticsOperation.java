package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.domain.service.PendingChangeStatisticsService;
import io.github.lumi.minecraft.world.WorldStateReader;
import io.github.lumi.minecraft.world.ChunkLoadSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Captures dirty sections on tick and calculates exact pending totals off-thread. */
public final class PendingStatisticsOperation implements DimensionMutation {
    private final CommitId head;
    private final WorkingIndexSnapshot boundary;
    private final List<SectionKey> sections;
    private final List<Zone> zones;
    private final WorldStateReader reader;
    private final Supplier<WorkingIndexSnapshot> currentBoundary;
    private final BooleanSupplier boundaryDurable;
    private final Calculator calculator;
    private final Executor background;
    private final LongSupplier nanoTime;
    private final ChunkLoadSession chunks;
    private final Map<SectionKey, SectionBlob> captured = new HashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private int next;
    private CompletableFuture<PendingChangeStatisticsService.Result> future;
    private PendingChangeStatisticsService.Result result;
    private Throwable failure;
    private boolean durabilityReady;
    private boolean chunksReady;
    private boolean chunksReleased;

    public PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            BooleanSupplier boundaryDurable,
            PendingChangeStatisticsService calculator,
            Executor background) {
        this(head, boundary, zones, reader, currentBoundary, boundaryDurable,
                calculator::calculate, background, System::nanoTime, null);
    }

    public PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            BooleanSupplier boundaryDurable,
            PendingChangeStatisticsService calculator,
            Executor background,
            ChunkLoadSession chunks) {
        this(head, boundary, zones, reader, currentBoundary, boundaryDurable,
                calculator::calculate, background, System::nanoTime,
                Objects.requireNonNull(chunks, "chunks"));
    }

    PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            BooleanSupplier boundaryDurable,
            Calculator calculator,
            Executor background,
            LongSupplier nanoTime) {
        this(head, boundary, zones, reader, currentBoundary, boundaryDurable,
                calculator, background, nanoTime, null);
    }

    private PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            BooleanSupplier boundaryDurable,
            Calculator calculator,
            Executor background,
            LongSupplier nanoTime,
            ChunkLoadSession chunks) {
        this.head = Objects.requireNonNull(head, "head");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
        this.reader = Objects.requireNonNull(reader, "reader");
        this.currentBoundary = Objects.requireNonNull(
                currentBoundary, "currentBoundary");
        this.boundaryDurable = Objects.requireNonNull(
                boundaryDurable, "boundaryDurable");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.background = Objects.requireNonNull(background, "background");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.chunks = chunks;
        sections = boundary.generations().keySet().stream()
                .filter(SectionKey.class::isInstance)
                .map(SectionKey.class::cast)
                .sorted(java.util.Comparator
                        .comparingInt(SectionKey::chunkX)
                        .thenComparingInt(SectionKey::sectionY)
                        .thenComparingInt(SectionKey::chunkZ))
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
    }

    @Override
    public boolean requiresFreeze() {
        return false;
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        if (isTerminal()) {
            return;
        }
        if (!durabilityReady) {
            if (!boundary.equals(currentBoundary.get())) {
                markStale();
                return;
            }
            if (!boundaryDurable.getAsBoolean()) {
                return;
            }
            durabilityReady = true;
        }
        if (future == null) {
            if (!chunksReady) {
                if (chunks != null && !chunks.loadUntil(deadlineNanos)) {
                    return;
                }
                chunksReady = true;
            }
            while (next < sections.size() && nanoTime.getAsLong() < deadlineNanos) {
                SectionKey key = sections.get(next++);
                captured.put(key, reader.read(key));
            }
            if (next < sections.size()) {
                return;
            }
            if (!boundary.equals(currentBoundary.get())) {
                markStale();
                return;
            }
            Map<SectionKey, SectionBlob> snapshot = Map.copyOf(captured);
            captured.clear();
            future = CompletableFuture.supplyAsync(
                    () -> calculate(snapshot), background);
            return;
        }
        if (!future.isDone()) {
            return;
        }
        try {
            PendingChangeStatisticsService.Result calculated = future.join();
            if (!boundary.equals(currentBoundary.get())) {
                markStale();
                return;
            }
            result = calculated;
            releaseChunks();
        } catch (CompletionException failed) {
            fail(failed.getCause() == null ? failed : failed.getCause());
        }
    }

    private PendingChangeStatisticsService.Result calculate(
            Map<SectionKey, SectionBlob> snapshot) {
        try {
            return calculator.calculate(head, snapshot, zones, cancelled::get);
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    public Optional<PendingChangeStatisticsService.Result> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public boolean isTerminal() {
        return result != null || failure != null || cancelled.get();
    }

    @Override
    public boolean isSafeToRelease() {
        return isTerminal();
    }

    @Override
    public MutationTerminalState terminalState() {
        if (!isTerminal()) {
            throw new IllegalStateException(
                    "Pending statistics are not terminal");
        }
        if (cancelled.get()) {
            return MutationTerminalState.CANCELLED;
        }
        return failure == null ? MutationTerminalState.SUCCEEDED
                : MutationTerminalState.FAILED;
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public MutationTerminalState unhandledFailureState() {
        return MutationTerminalState.FAILED;
    }

    @Override
    public OperationProgress progress() {
        if (!durabilityReady) {
            return OperationProgress.indeterminate(
                    "Waiting for pending-statistics writes");
        }
        if (!chunksReady && chunks != null) {
            return new OperationProgress(
                    "Loading pending-statistics chunks",
                    chunks.completedChunks(), chunks.totalChunks());
        }
        if (future != null) {
            return OperationProgress.indeterminate(
                    "Calculating pending block statistics");
        }
        return new OperationProgress(
                "Capturing pending block statistics", next, sections.size());
    }

    @Override
    public boolean cancel() {
        if (isTerminal()) {
            return false;
        }
        markStale();
        return true;
    }

    @Override
    public void close() {
        if (!isTerminal()) {
            markStale();
        } else {
            releaseChunks();
        }
    }

    private void markStale() {
        cancelled.set(true);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        captured.clear();
        releaseChunks();
    }

    private void fail(Throwable failed) {
        failure = Objects.requireNonNull(failed, "failed");
        releaseChunks();
    }

    private void releaseChunks() {
        if (!chunksReleased && chunks != null) {
            chunks.close();
            chunksReleased = true;
        }
    }

    @FunctionalInterface
    interface Calculator {
        PendingChangeStatisticsService.Result calculate(
                CommitId head,
                Map<SectionKey, SectionBlob> current,
                List<Zone> zones,
                BooleanSupplier cancelled) throws IOException;
    }
}
