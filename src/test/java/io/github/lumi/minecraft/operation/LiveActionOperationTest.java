package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.LiveBlockWorldAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveActionOperationTest {
    private static final UUID PLAYER = new UUID(0, 7);
    private static final BlockPosition POSITION = new BlockPosition(1, 2, 3);

    @Test
    void validatesEverythingBeforeMutationAndCompletesUndo() throws IOException {
        LiveActionJournal journal = action(block("stone"), block("gold_block"));
        FakeWorld world = new FakeWorld(block("gold_block"));
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO, world, () -> 0L);

        operation.advance(Long.MAX_VALUE);

        assertEquals(block("stone"), world.states.get(POSITION));
        assertEquals(1, world.writes);
        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
        assertTrue(journal.prepareRedo(PLAYER).isPresent());
    }

    @Test
    void refusesAtomicallyWhenVisibleStateConflicts() throws IOException {
        LiveActionJournal journal = action(block("stone"), block("gold_block"));
        FakeWorld world = new FakeWorld(block("diamond_block"));
        var cancelled = new ArrayList<UUID>();
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO, world,
                () -> 0L, cancelled::add);

        operation.advance(Long.MAX_VALUE);

        assertEquals(0, world.writes);
        assertEquals(1, cancelled.size());
        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertTrue(journal.prepareUndo(PLAYER).isPresent());
    }

    @Test
    void repairsOnceAndKeepsDimensionFrozenAfterSecondMismatch() throws IOException {
        LiveActionJournal journal = action(block("stone"), block("gold_block"));
        FakeWorld world = new FakeWorld(block("gold_block"));
        world.ignoredWrites = 2;
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO, world, () -> 0L);

        operation.advance(Long.MAX_VALUE);

        assertEquals(2, world.writes);
        assertEquals(MutationTerminalState.DEGRADED, operation.terminalState());
        assertTrue(!operation.isSafeToRelease());
    }

    private static LiveActionJournal action(BlockSnapshot before, BlockSnapshot after) {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER);
        journal.record(action, POSITION, before, after);
        journal.close(action);
        return journal;
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }

    private static final class FakeWorld implements LiveBlockWorldAccess {
        private final Map<BlockPosition, BlockSnapshot> states = new HashMap<>();
        private int ignoredWrites;
        private int writes;

        private FakeWorld(BlockSnapshot state) {
            states.put(POSITION, state);
        }

        @Override
        public BlockSnapshot read(BlockPosition position) {
            return states.get(position);
        }

        @Override
        public void write(BlockPosition position, BlockSnapshot state) {
            writes++;
            if (ignoredWrites > 0) {
                ignoredWrites--;
            } else {
                states.put(position, state);
            }
        }
    }
}
