package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                (head, sections, zones) -> {
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
    void rejectsAResultWhenTheDirtyGenerationMoves() throws Exception {
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(
                Map.of(SECTION, 1L));
        AtomicReference<WorkingIndexSnapshot> current =
                new AtomicReference<>(boundary);
        var operation = new PendingStatisticsOperation(
                HEAD, boundary, List.of(), reader(), current::get,
                (head, sections, zones) -> {
                    current.set(new WorkingIndexSnapshot(Map.of(SECTION, 2L)));
                    return result(PendingChangeStatistics.NONE);
                },
                Runnable::run, System::nanoTime);

        operation.advance(Long.MAX_VALUE);
        operation.advance(Long.MAX_VALUE);

        assertTrue(operation.result().isEmpty());
        assertTrue(operation.failure().isPresent());
        assertEquals(MutationTerminalState.FAILED,
                operation.terminalState());
    }

    private static WorldStateReader reader() {
        return new WorldStateReader() {
            @Override
            public SectionBlob read(SectionKey key) {
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
