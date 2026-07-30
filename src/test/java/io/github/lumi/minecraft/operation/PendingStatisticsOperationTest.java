package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.PendingChangeStatisticsService;
import io.github.lumi.minecraft.world.WorldStateReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PendingStatisticsOperationTest {
    private static final CommitId HEAD =
            new CommitId(new ObjectId("1".repeat(64)));
    private static final SectionKey SECTION = new SectionKey(0, 4, 0);

    @Test
    void capturesSectionsBeforeCalculatingAwayFromTheTick() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        AtomicReference<Map<SectionKey, SectionBlob>> calculated =
                new AtomicReference<>();
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader(), () -> boundary,
                () -> true,
                (head, sections, zones, cancelled) -> {
                    calculated.set(sections);
                    return result(new PendingChangeStatistics(2, 1, 3));
                },
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertEquals(Map.of(SECTION, section()), calculated.get());
        assertEquals(new PendingChangeStatistics(2, 1, 3),
                operation.result().orElseThrow().workspace());
        assertEquals(MutationTerminalState.SUCCEEDED,
                operation.terminalState());
    }

    @Test
    void cancelsAResultWhenTheDirtyGenerationMoves() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        AtomicReference<WorkingIndexSnapshot> current =
                new AtomicReference<>(boundary);
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader(), current::get,
                () -> true,
                (head, sections, zones, cancelled) -> {
                    current.set(new WorkingIndexSnapshot(Map.of(SECTION, 2L)));
                    return result(PendingChangeStatistics.NONE);
                },
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertTrue(operation.result().isEmpty());
        assertTrue(operation.failure().isEmpty());
        assertEquals(MutationTerminalState.CANCELLED,
                operation.terminalState());
    }

    @Test
    void cancelsBeforeDurabilityWhenTheBoundaryMoves() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        WorkingIndexSnapshot moved = new WorkingIndexSnapshot(
                Map.of(SECTION, 2L));
        AtomicInteger reads = new AtomicInteger();
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader(reads::incrementAndGet),
                () -> moved, () -> false,
                (head, sections, zones, cancelled) ->
                        result(PendingChangeStatistics.NONE),
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);

        assertEquals(0, reads.get());
        assertTrue(operation.failure().isEmpty());
        assertEquals(MutationTerminalState.CANCELLED,
                operation.terminalState());
    }

    @Test
    void cancelsAfterCaptureWhenTheBoundaryMoves() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        WorkingIndexSnapshot moved = new WorkingIndexSnapshot(
                Map.of(SECTION, 2L));
        AtomicInteger boundaryReads = new AtomicInteger();
        AtomicBoolean calculated = new AtomicBoolean();
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader(),
                () -> boundaryReads.incrementAndGet() == 1 ? boundary : moved,
                () -> true,
                (head, sections, zones, cancelled) -> {
                    calculated.set(true);
                    return result(PendingChangeStatistics.NONE);
                },
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);

        assertFalse(calculated.get());
        assertTrue(operation.failure().isEmpty());
        assertEquals(MutationTerminalState.CANCELLED,
                operation.terminalState());
    }

    @Test
    void waitsForTheCapturedBoundaryToBecomeDurable() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        AtomicBoolean durable = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        WorldStateReader reader = reader(() -> reads.incrementAndGet());
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader, () -> boundary, durable::get,
                (head, sections, zones, cancelled) ->
                        result(PendingChangeStatistics.NONE),
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);

        assertEquals(0, reads.get());
        assertEquals("Waiting for pending-statistics writes",
                operation.progress().phase());

        durable.set(true);
        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertEquals(1, reads.get());
        assertEquals(MutationTerminalState.SUCCEEDED,
                operation.terminalState());
    }

    @Test
    void cancelsBeforeReadingWithoutReportingFailure() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        AtomicInteger reads = new AtomicInteger();
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(),
                reader(reads::incrementAndGet), () -> boundary, () -> false,
                (head, sections, zones, cancelled) ->
                        result(PendingChangeStatistics.NONE),
                Runnable::run, System::nanoTime);

        assertTrue(operation.cancel());

        operation.advance(Long.MAX_VALUE);
        assertEquals(0, reads.get());
        assertTrue(operation.failure().isEmpty());
        assertEquals(MutationTerminalState.CANCELLED,
                operation.terminalState());
        assertFalse(operation.cancel());
    }

    private static WorldStateReader reader() {
        return reader(() -> { });
    }

    private static WorldStateReader reader(Runnable beforeRead) {
        return new WorldStateReader() {
            @Override
            public SectionBlob read(SectionKey key) {
                beforeRead.run();
                return section();
            }

            @Override
            public io.github.lumi.domain.model.EntityChunkBlob read(
                    io.github.lumi.domain.model.EntityChunkKey key) {
                throw new AssertionError("Entity chunks are not block statistics");
            }

            @Override
            public Map<java.util.UUID, io.github.lumi.domain.model.PlayerSpawn>
                    readPlayerSpawns() {
                return Map.of();
            }
        };
    }

    private static SectionBlob section() {
        return new SectionBlob(
                new ArrayList<>(java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air")),
                Map.of());
    }

    private static PendingChangeStatisticsService.Result result(
            PendingChangeStatistics workspace) {
        return new PendingChangeStatisticsService.Result(
                workspace, Map.of());
    }
}
