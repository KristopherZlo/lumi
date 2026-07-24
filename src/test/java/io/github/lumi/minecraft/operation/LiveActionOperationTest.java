package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.LiveBlockWorldAccess;
import io.github.lumi.minecraft.world.LiveEntityWorldAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveActionOperationTest {
    private static final UUID PLAYER = new UUID(0, 7);
    private static final UUID ENTITY = new UUID(0, 8);
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
    void undoesOneTntWaveUnderOneOperation() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER);
        journal.record(first, POSITION, block("stone"), block("dirt"));
        journal.close(first);
        UUID second = journal.begin(PLAYER);
        journal.record(second, POSITION, block("dirt"), block("gold_block"));
        journal.close(second);
        FakeWorld world = new FakeWorld(block("gold_block"));
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                world, LiveEntityWorldAccess.UNSUPPORTED,
                new LiveActionOperation.PendingCancellation() {
                    @Override public boolean cancel(UUID action) { return false; }
                    @Override public java.util.Set<UUID> tntWaveActions(UUID player) {
                        return java.util.Set.of(first, second);
                    }
                }, ignored -> { });

        operation.advance(Long.MAX_VALUE);

        assertEquals(block("stone"), world.states.get(POSITION));
        assertEquals(2, world.writes);
        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
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
    void preexistingBlockConflictDoesNotCancelOwnedCarrierOrRetainFreeze() {
        LiveActionJournal journal = action(block("stone"), block("redstone_block"));
        FakeWorld world = new FakeWorld(block("air"));
        int[] cancellations = {0};
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                world, LiveEntityWorldAccess.UNSUPPORTED, action -> {
                    cancellations[0]++;
                    return true;
                }, ignored -> { });

        operation.advance(Long.MAX_VALUE);

        assertEquals(0, cancellations[0]);
        assertEquals(0, world.writes);
        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertTrue(operation.isSafeToRelease());
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

    @Test
    void reselectsFinalStateAfterCancellingOwnedCarrier() throws IOException {
        LiveActionJournal journal = action(block("stone"), block("moving_piston"));
        FakeWorld world = new FakeWorld(block("piston_head"));
        UUID action = journal.prepareUndo(PLAYER).orElseThrow().actionId();
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO, world, () -> 0L,
                ignored -> journal.record(
                        action, POSITION, block("moving_piston"), block("piston_head")));

        operation.advance(Long.MAX_VALUE);

        assertEquals(block("stone"), world.states.get(POSITION));
        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
    }

    @Test
    void undoAndRedoReplayCapturedBlockOrder() throws IOException {
        BlockPosition trigger = new BlockPosition(2, 2, 3);
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER);
        journal.record(action, trigger, block("air"), block("redstone_block"));
        journal.record(action, POSITION, block("tnt"), block("air"));
        journal.close(action);
        FakeWorld world = new FakeWorld(block("air"));
        world.states.put(trigger, block("redstone_block"));

        LiveActionOperation undo = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO, world, () -> 0L);
        undo.advance(Long.MAX_VALUE);

        assertEquals(java.util.List.of(trigger, POSITION), world.writeOrder);
        assertEquals(block("air"), world.states.get(trigger));
        assertEquals(block("tnt"), world.states.get(POSITION));
        assertEquals(MutationTerminalState.SUCCEEDED, undo.terminalState());
        world.writeOrder.clear();

        LiveActionOperation redo = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.REDO, world, () -> 0L);
        redo.advance(Long.MAX_VALUE);

        assertEquals(java.util.List.of(trigger, POSITION), world.writeOrder);
        assertEquals(block("air"), world.states.get(POSITION));
        assertEquals(block("redstone_block"), world.states.get(trigger));
        assertEquals(MutationTerminalState.SUCCEEDED, redo.terminalState());
    }

    @Test
    void cancellationFailureRetainsFreezeAfterOwnershipChangesMayStart() {
        LiveActionOperation operation = new LiveActionOperation(
                action(block("stone"), block("gold_block")), PLAYER,
                LiveActionJournal.Direction.UNDO, new FakeWorld(block("gold_block")),
                () -> 0L, ignored -> { throw new IllegalStateException("cancel failed"); });

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.DEGRADED, operation.terminalState());
        assertTrue(!operation.isSafeToRelease());
    }

    @Test
    void removesEntityAddedByActionAndRestoresItOnRedo() throws IOException {
        EntityState entity = entity(1);
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER);
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(entity));
        journal.close(action);
        FakeEntityWorld entities = new FakeEntityWorld(Optional.of(entity));
        LiveActionOperation undo = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                new FakeWorld(block("air")), entities, ignored -> { });

        undo.advance(Long.MAX_VALUE);

        assertEquals(Optional.empty(), entities.state);
        assertEquals(MutationTerminalState.SUCCEEDED, undo.terminalState());

        LiveActionOperation redo = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.REDO,
                new FakeWorld(block("air")), entities, ignored -> { });
        redo.advance(Long.MAX_VALUE);

        assertEquals(Optional.of(entity), entities.state);
        assertEquals(MutationTerminalState.SUCCEEDED, redo.terminalState());
    }

    @Test
    void entityConflictRefusesEntireMixedActionBeforeBlockWrite() throws IOException {
        EntityState expected = entity(1);
        LiveActionJournal journal = action(block("stone"), block("gold_block"));
        UUID action = journal.prepareUndo(PLAYER).orElseThrow().actionId();
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(expected));
        FakeWorld blocks = new FakeWorld(block("gold_block"));
        FakeEntityWorld entities = new FakeEntityWorld(Optional.of(entity(2)));
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                blocks, entities, ignored -> { });

        operation.advance(Long.MAX_VALUE);

        assertEquals(0, blocks.writes);
        assertEquals(0, entities.writes);
        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
    }

    @Test
    void missingPreparedEntityRefusesWholeActionBeforeBlockWrite() throws IOException {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER);
        journal.record(action, POSITION, block("stone"), block("gold_block"));
        journal.recordEntity(
                action, ENTITY, Optional.of(entity(1)), Optional.empty());
        journal.close(action);
        FakeWorld blocks = new FakeWorld(block("gold_block"));
        FakeEntityWorld entities = new FakeEntityWorld(Optional.empty());
        entities.prepared = false;
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                blocks, entities, ignored -> { });

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.FAILED, operation.terminalState());
        assertTrue(operation.isSafeToRelease());
        assertEquals(0, blocks.writes);
        assertEquals(0, entities.writes);
    }

    @Test
    void reselectsSettledEntityBeforeConflictValidation() throws IOException {
        EntityState initial = entity(1);
        EntityState settled = entity(2);
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER);
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(initial));
        journal.close(action);
        FakeEntityWorld entities = new FakeEntityWorld(Optional.of(settled));
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                new FakeWorld(block("air")), entities,
                ignored -> journal.recordEntity(
                        action, ENTITY, Optional.empty(), Optional.of(settled)));

        operation.advance(Long.MAX_VALUE);

        assertEquals(Optional.empty(), entities.state);
        assertEquals(MutationTerminalState.SUCCEEDED, operation.terminalState());
    }

    @Test
    void retainsFreezeWhenDirtyPublicationFails() throws IOException {
        LiveActionJournal journal = action(block("stone"), block("gold_block"));
        LiveActionOperation operation = new LiveActionOperation(
                journal, PLAYER, LiveActionJournal.Direction.UNDO,
                new FakeWorld(block("gold_block")), new FakeEntityWorld(Optional.empty()),
                ignored -> false, ignored -> {
                    throw new IllegalStateException("working index unavailable");
                });

        operation.advance(Long.MAX_VALUE);

        assertEquals(MutationTerminalState.DEGRADED, operation.terminalState());
        assertTrue(!operation.isSafeToRelease());
        assertTrue(journal.prepareUndo(PLAYER).isPresent());
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

    private static EntityState entity(int state) {
        return new EntityState(
                ENTITY, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) state}));
    }

    private static final class FakeWorld implements LiveBlockWorldAccess {
        private final Map<BlockPosition, BlockSnapshot> states = new HashMap<>();
        private int ignoredWrites;
        private int writes;
        private final java.util.List<BlockPosition> writeOrder = new ArrayList<>();

        private FakeWorld(BlockSnapshot state) {
            states.put(POSITION, state);
        }

        @Override
        public void requirePrepared(BlockSnapshot state) { }

        @Override
        public BlockSnapshot read(BlockPosition position) {
            return states.get(position);
        }

        @Override
        public void write(BlockPosition position, BlockSnapshot state) {
            writes++;
            writeOrder.add(position);
            if (ignoredWrites > 0) {
                ignoredWrites--;
            } else {
                states.put(position, state);
            }
        }
    }

    private static final class FakeEntityWorld implements LiveEntityWorldAccess {
        private Optional<EntityState> state;
        private boolean prepared = true;
        private int writes;

        private FakeEntityWorld(Optional<EntityState> state) {
            this.state = state;
        }

        @Override
        public Optional<EntityState> read(UUID entityId) {
            return state;
        }

        @Override
        public void requirePrepared(Optional<EntityState> replacement) throws IOException {
            if (!prepared && replacement.isPresent()) {
                throw new IOException("not prepared");
            }
        }

        @Override
        public void write(UUID entityId, Optional<EntityState> replacement) {
            writes++;
            state = replacement;
        }
    }
}
