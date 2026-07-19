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
    private final Calculator calculator;
    private final Executor background;
    private final LongSupplier nanoTime;
    private final ChunkLoadSession chunks;
    private final Map<SectionKey, SectionBlob> captured = new HashMap<>();
    private int next;
    private CompletableFuture<PendingChangeStatisticsService.Result> future;
    private PendingChangeStatisticsService.Result result;
    private Throwable failure;
    private boolean chunksReady;
    private boolean chunksReleased;

    public PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            PendingChangeStatisticsService calculator,
            Executor background) {
        this(head, boundary, zones, reader, currentBoundary,
                calculator::calculate, background, System::nanoTime, null);
    }

    public PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            PendingChangeStatisticsService calculator,
            Executor background,
            ChunkLoadSession chunks) {
        this(head, boundary, zones, reader, currentBoundary,
                calculator::calculate, background, System::nanoTime,
                Objects.requireNonNull(chunks, "chunks"));
    }

    PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
            Calculator calculator,
            Executor background,
            LongSupplier nanoTime) {
        this(head, boundary, zones, reader, currentBoundary,
                calculator, background, nanoTime, null);
    }

    private PendingStatisticsOperation(
            CommitId head,
            WorkingIndexSnapshot boundary,
            List<Zone> zones,
            WorldStateReader reader,
            Supplier<WorkingIndexSnapshot> currentBoundary,
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
                fail(new IOException(
                        "Pending changes moved while statistics were captured"));
                return;
            }
            future = CompletableFuture.supplyAsync(this::calculate, background);
            return;
        }
        if (!future.isDone()) {
            return;
        }
        try {
            PendingChangeStatisticsService.Result calculated = future.join();
            if (!boundary.equals(currentBoundary.get())) {
                fail(new IOException(
                        "Pending changes moved while statistics were calculated"));
                return;
            }
            result = calculated;
            releaseChunks();
        } catch (CompletionException failed) {
            fail(failed.getCause() == null ? failed : failed.getCause());
        }
    }

    private PendingChangeStatisticsService.Result calculate() {
        try {
            return calculator.calculate(head, Map.copyOf(captured), zones);
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }

    public Optional<PendingChangeStatisticsService.Result> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public boolean isTerminal() {
        return result != null || failure != null;
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
        return failure == null
                ? MutationTerminalState.SUCCEEDED
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
    public void close() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
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
                List<Zone> zones) throws IOException;
    }
}
