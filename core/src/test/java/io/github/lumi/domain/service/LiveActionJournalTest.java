package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.storage.repository.WorldActionHistoryRepository;
import io.github.lumi.domain.model.ObjectId;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveActionJournalTest {
    @Test void lateBlastFollowsNewerSectionOwnershipBeforeTheThirdPlacement() {
        var journal = new LiveActionJournal();
        var key = new SectionKey(0, 0, 0);
        var first = new BlockPosition(1, 0, 0);
        var second = new BlockPosition(0, 0, 0);
        var third = new BlockPosition(2, 0, 0);
        UUID blast = journal.begin(PLAYER_A);
        journal.record(blast, first, block("stone"), block("air"));
        journal.close(blast);
        var empty = new SectionBlob(java.util.Collections.nCopies(4096, "minecraft:air"), Map.of());
        var states = new java.util.ArrayList<>(empty.blockStates());
        states.set(0, "minecraft:tnt");
        UUID placement = journal.begin(PLAYER_A);
        journal.recordSection(placement, key, empty, new SectionBlob(states, Map.of()));
        journal.close(placement);
        UUID owner = journal.record(blast, second, block("tnt"), block("air"));
        assertEquals(placement, owner, "A late blast must follow compact section ownership too");
        journal.mergeGroups(blast, owner);
        UUID next = journal.begin(PLAYER_A);
        journal.record(next, third, block("air"), block("tnt"));
        journal.close(next);
        journal.mergeGroups(blast, next);
        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertTrue(undo.sections().isEmpty(), "The placed-and-destroyed TNT has no remaining delta");
        assertTrue(!undo.expected().containsKey(second));
        assertEquals(block("air"), undo.expected().get(first));
        assertEquals(block("tnt"), undo.expected().get(third));
        assertEquals(block("stone"), undo.replacement().get(first));
        assertEquals(block("air"), undo.replacement().get(third));
    }

    @Test void compactSectionsKeepChangedCellOwnershipAndAbsorbLaterCausalWrites(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var journal = new LiveActionJournal();
        var key = new SectionKey(0, 0, 0);
        var empty = new SectionBlob(java.util.Collections.nCopies(4096, "minecraft:air"), Map.of());
        var states = new java.util.ArrayList<>(empty.blockStates());
        states.set(0, "minecraft:stone");
        var placed = new SectionBlob(states, Map.of());
        UUID action = journal.begin(PLAYER_A);
        journal.recordSection(action, key, empty, placed);
        journal.close(action);
        journal.record(action, new BlockPosition(0, 0, 0), block("stone"), block("gold_block"));
        var delta = journal.prepareUndo(PLAYER_A).orElseThrow().sections().get(key);
        assertEquals("minecraft:air", delta.before().blockStates().getFirst());
        assertEquals("minecraft:gold_block", delta.after().blockStates().getFirst());
        UUID foreign = journal.begin(PLAYER_B);
        journal.record(foreign, new BlockPosition(1, 0, 0), block("air"), block("diamond_block"));
        journal.close(foreign);
        assertEquals(action, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        UUID overlap = journal.begin(PLAYER_B);
        journal.record(overlap, new BlockPosition(0, 0, 0), block("gold_block"), block("dirt"));
        journal.close(overlap);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
        var reopened = new LiveActionJournal();
        reopened.restore(journal.snapshotIndex(), journal::snapshotAction);
        assertThrows(IllegalStateException.class, () -> reopened.prepareUndo(PLAYER_A));
        journal.complete(journal.prepareUndo(PLAYER_B).orElseThrow());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        assertEquals(action, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
        try (var spool = new io.github.lumi.storage.repository.NbtSpool(root, 64, 4096)) {
            var nbt = new CanonicalNbt(new byte[128 << 10]);
            var paged = spool.store(new SectionBlob(placed.blockStates(), Map.of(0, nbt)));
            var bounded = new LiveActionJournal(new LiveActionJournal.Limits(4, 48 << 10, 64 << 10, 128 << 10));
            UUID large = bounded.begin(PLAYER_A);
            bounded.recordSection(large, key, paged, empty);
            bounded.close(large);
            assertEquals(large, bounded.prepareUndo(PLAYER_A).orElseThrow().actionId());
            var version = paged.dataVersion();
            var history = new WorldActionHistory(new io.github.lumi.storage.repository.WorldActionHistoryRepository(root, version, spool), version);
            UUID workspace = UUID.randomUUID();
            history.flush(workspace, new BranchName("main"), bounded.snapshotIndex(), bounded::snapshotAction, () -> true);
            var restored = new LiveActionJournal(new LiveActionJournal.Limits(4, 48 << 10, 64 << 10, 128 << 10));
            history.restore(restored, workspace, new BranchName("main"));
            var before = restored.prepareUndo(PLAYER_A).orElseThrow().sections().get(key).before().blockEntities().get(0);
            assertEquals(0, before.residentByteSize());
            org.junit.jupiter.api.Assertions.assertArrayEquals(nbt.bytes(), before.bytes());
        }
    }

    @Test void biomeActionsShareOrderingAndPreserveEarlierRedoAfterANoop() throws Exception {
        var journal = new LiveActionJournal();
        var position = new io.github.lumi.domain.model.BiomePosition(-1, -1, -1);
        UUID action = journal.begin(PLAYER_A);
        journal.recordBiome(action, position, "minecraft:plains", "minecraft:desert");
        journal.close(action);
        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals("minecraft:plains", undo.biomes().get(position).before());
        UUID foreign = journal.begin(PLAYER_B);
        journal.recordBiome(foreign, position, "minecraft:desert", "minecraft:forest");
        journal.close(foreign);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
        journal.complete(journal.prepareUndo(PLAYER_B).orElseThrow());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        UUID noop = journal.begin(PLAYER_A);
        journal.recordBiome(noop, position, "minecraft:plains", "minecraft:plains");
        assertTrue(!journal.close(noop));
        var reopened = new LiveActionJournal();
        reopened.restore(journal.snapshotIndex(), journal::snapshotAction);
        assertEquals(action, reopened.prepareRedo(PLAYER_A).orElseThrow().actionId());
        assertEquals(new io.github.lumi.domain.model.SectionKey(-1, -1, -1), position.section());
        assertEquals(63, position.localIndex());
    }

    @Test void publishesDurableCursorsOnceAndKeepsTheLastRootOnCaptureFailure(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var repository = new io.github.lumi.storage.repository.WorldActionHistoryRepository(root);
        var version = new io.github.lumi.domain.model.WorldDataVersion(5000);
        UUID workspace = UUID.randomUUID();
        var branch = new BranchName("main");
        var journal = new LiveActionJournal();
        add(journal, 1);
        UUID action = add(journal, 2);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        var history = new WorldActionHistory(repository, version);
        var index = journal.snapshotIndex();
        assertTrue(history.flush(workspace, branch, index, journal::snapshotAction, () -> true));
        var beforeReplacement = repository.readIndex().orElseThrow();
        var file = root.resolve("actions/index.bin.gz");
        java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(12_000));
        var unchanged = java.nio.file.Files.getLastModifiedTime(file);
        assertTrue(history.flush(workspace, branch, index, journal::snapshotAction, () -> true));
        assertTrue(!history.flush(workspace, branch, index, journal::snapshotAction, () -> false));
        assertEquals(unchanged, java.nio.file.Files.getLastModifiedTime(file));
        UUID replacement = UUID.randomUUID();
        var nextContext = new io.github.lumi.domain.model.WorldActionContext(UUID.randomUUID(), new BranchName("next"));
        var context = new io.github.lumi.domain.model.WorldActionContext.Change(
                new io.github.lumi.domain.model.WorldActionContext(workspace, branch), nextContext);
        history.prepareCheckpoint(replacement, PLAYER_A, checkpoint('4').withContext(context), workspace, branch);
        assertEquals(beforeReplacement, repository.readIndex().orElseThrow());
        var appliedReplacement = history.publishCheckpoint(replacement, true);
        assertEquals(nextContext.workspace(), appliedReplacement.workspace());
        assertEquals(nextContext.branch(), appliedReplacement.branch());
        assertEquals(replacement, appliedReplacement.index().players().get(PLAYER_A).undo().getLast());
        assertTrue(appliedReplacement.index().players().get(PLAYER_A).redo().isEmpty());
        journal.installIndex(appliedReplacement.index(), metadata -> history.readRecord(appliedReplacement, metadata));
        assertEquals(replacement, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        assertEquals(appliedReplacement, history.publishCheckpoint(replacement, true));
        var trimmed = appliedReplacement.index().appendCheckpoint(UUID.randomUUID(), PLAYER_A,
                appliedReplacement.index().actions().getLast().checkpoint().orElseThrow(), 1);
        assertEquals(1, trimmed.players().get(PLAYER_A).undo().size());
        assertTrue(trimmed.unsafeBeforeSequence() >= appliedReplacement.index().nextSequence());
        var switchStep = new io.github.lumi.domain.model.ActionReplayTarget(PLAYER_A, replacement, true);
        history.publishReplay(UUID.randomUUID(), switchStep, false);
        assertEquals(workspace, repository.readIndex().orElseThrow().workspace());
        history.publishReplay(UUID.randomUUID(), switchStep, true);
        assertEquals(nextContext.workspace(), repository.readIndex().orElseThrow().workspace());
        var returned = history.publishCheckpoint(replacement, false);
        journal.installIndex(returned.index(), metadata -> history.readRecord(returned, metadata));
        assertEquals(action, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
        assertEquals(beforeReplacement.index().players(), repository.readIndex().orElseThrow().index().players());
        assertTrue(history.hasPreparedCheckpoint(replacement));
        var reopened = new LiveActionJournal();
        new WorldActionHistory(repository, version).restore(reopened, workspace, branch);
        assertEquals(action, reopened.prepareRedo(PLAYER_A).orElseThrow().actionId());
        var step = new io.github.lumi.domain.model.ActionReplayTarget(PLAYER_A, action, false);
        UUID operation = UUID.randomUUID();
        history.publishReplay(operation, step, true);
        var published = repository.readIndex().orElseThrow();
        history.publishReplay(operation, step, true);
        assertEquals(published, repository.readIndex().orElseThrow());
        assertTrue(!history.flush(workspace, branch, index, journal::snapshotAction, () -> true));
        reopened = new LiveActionJournal();
        new WorldActionHistory(repository, version).restore(reopened, workspace, branch);
        assertEquals(action, reopened.prepareUndo(PLAYER_A).orElseThrow().actionId());
        add(reopened, 3);
        var current = reopened.snapshotIndex();
        assertThrows(java.io.IOException.class, () -> history.flush(workspace, branch, current,
                metadata -> { throw new java.io.IOException("injected capture failure"); }, () -> true));
        assertEquals(published, repository.readIndex().orElseThrow());
        UUID foreign = reopened.begin(PLAYER_B);
        reopened.record(foreign, new BlockPosition(40, 0, 0), block("air"), block("diamond_block"));
        reopened.close(foreign);
        assertTrue(history.flush(workspace, branch, reopened.snapshotIndex(), reopened::snapshotAction, () -> true));
        var cleared = history.forget(PLAYER_A);
        assertTrue(cleared.index().actions().stream().noneMatch(entry -> entry.actor().equals(PLAYER_A)));
        var afterClear = new LiveActionJournal();
        new WorldActionHistory(repository, version).restore(afterClear, workspace, branch);
        assertTrue(afterClear.prepareUndo(PLAYER_A).isEmpty());
        assertEquals(foreign, afterClear.prepareUndo(PLAYER_B).orElseThrow().actionId());
    }

    @Test void nativeReplayIntentRetainsExactStepAndSurvivesInterruptedPublication(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var journal = new LiveActionJournal();
        add(journal, 2);
        UUID action = add(journal, 1);
        var version = new WorldDataVersion(1);
        var workspace = UUID.randomUUID();
        var branch = new BranchName("main");
        var repository = new WorldActionHistoryRepository(root);
        var history = new WorldActionHistory(repository, version);
        assertTrue(history.flush(workspace, branch, journal.snapshotIndex(), journal::snapshotAction, () -> true));
        var plan = journal.prepareUndo(PLAYER_A).orElseThrow();
        UUID operation = UUID.randomUUID();
        history.beginNativeReplay(operation, plan);
        var otherVersion = new WorldDataVersion(2);
        assertThrows(java.io.IOException.class, () -> new WorldActionHistory(
                new WorldActionHistoryRepository(root, otherVersion), otherVersion));
        var reopened = new WorldActionHistory(repository, version);
        var intent = reopened.pendingNativeReplay().orElseThrow();
        assertEquals(plan, reopened.nativeReplayPlan(intent));
        assertThrows(java.io.IOException.class, () -> reopened.beginNativeReplay(UUID.randomUUID(), plan));
        assertThrows(java.io.IOException.class, () -> reopened.clearNativeReplay(UUID.randomUUID()));
        repository.removeUnreferencedPayloads();
        assertEquals(plan, reopened.nativeReplayPlan(intent));
        assertEquals(action, repository.readIndex().orElseThrow().index().players().get(PLAYER_A).undo().getLast());
        reopened.publishReplay(operation, intent.publication().orElseThrow().step(), false);
        // A crash after the durable cursor, before intent deletion, must not replay the step twice.
        assertTrue(new WorldActionHistory(repository, version).pendingNativeReplay().isEmpty());
        var restored = new LiveActionJournal();
        new WorldActionHistory(repository, version).restore(restored, workspace, branch);
        assertEquals(action, restored.prepareRedo(PLAYER_A).orElseThrow().actionId());
        var redo = restored.prepareRedo(PLAYER_A).orElseThrow();
        reopened.beginNativeReplay(operation, redo);
        assertEquals(redo, reopened.nativeReplayPlan(reopened.pendingNativeReplay().orElseThrow()));
        var retainedIntent = reopened.pendingNativeReplay().orElseThrow();
        reopened.forget(PLAYER_A);
        repository.removeUnreferencedPayloads();
        assertEquals(redo, reopened.nativeReplayPlan(retainedIntent));
        reopened.clearNativeReplay(operation);
        reopened.clearNativeReplay(operation);
        repository.removeUnreferencedPayloads();
        assertTrue(reopened.pendingNativeReplay().isEmpty());
    }

    @Test void reopensUndoRedoAndRefusesAnInterruptedProducer() throws Exception {
        var journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        UUID second = add(journal, 2);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        var index = journal.snapshotIndex();
        var reopened = new LiveActionJournal();
        reopened.restore(index, journal::snapshotAction);
        assertEquals(first, reopened.prepareUndo(PLAYER_A).orElseThrow().actionId());
        assertEquals(second, reopened.prepareRedo(PLAYER_A).orElseThrow().actionId());
        assertEquals(List.of(first), reopened.view(PLAYER_A).undo().stream().map(io.github.lumi.domain.model.WorldActionView.Entry::action).toList());
        assertEquals(List.of(second), reopened.view(PLAYER_A).redo().stream().map(io.github.lumi.domain.model.WorldActionView.Entry::action).toList());
        var step = new io.github.lumi.domain.model.ActionReplayTarget(PLAYER_A, second, false);
        var projected = index.move(step, true);
        assertEquals(projected, projected.move(step, true));
        reopened.complete(reopened.prepareRedo(PLAYER_A).orElseThrow());
        assertEquals(reopened.snapshotIndex().players(), projected.players());
        assertEquals(second, reopened.prepareUndo(PLAYER_A).orElseThrow().actionId());

        journal.retain(second); // Entity ownership alone does not mean a producer is unfinished.
        assertTrue(!journal.snapshotIndex().actions().getLast().pending());
        journal.retainDelayed(second);
        var interrupted = new LiveActionJournal();
        interrupted.restore(journal.snapshotIndex(), journal::snapshotAction);
        assertTrue(interrupted.prepareRedo(PLAYER_A).isEmpty());
        assertTrue(interrupted.lastUnavailableReason(PLAYER_A).isPresent());
        journal.releaseDelayed(second);
        journal.release(second);
    }

    @Test void unchangedBlockAndEntityRecordsPreserveEarlierRedo() {
        for (boolean entityRecord : List.of(false, true)) {
            var journal = new LiveActionJournal();
            UUID first = add(journal, 1);
            journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
            UUID empty = journal.begin(PLAYER_A);
            if (entityRecord) journal.recordEntity(empty, ENTITY, Optional.of(entity(1)), Optional.of(entity(1)));
            else journal.record(empty, POSITION, block("stone"), block("stone"));
            assertTrue(!journal.close(empty));
            assertEquals(first, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
        }
    }

    @Test void entityEndpointsReleaseCoalescedStatesButKeepUnfinishedOwners() {
        var prepared = new HashSet<EntityState>(List.of(entity(1), entity(2), entity(3)));
        var references = new LiveEntityStateReferences(prepared::remove);
        var journal = new LiveActionJournal(LiveActionJournal.Limits.DEFAULT,
                ignored -> { }, ignored -> { }, references);
        var before = Optional.of(entity(1));
        references.retain(before);
        UUID action = journal.begin(PLAYER_A);
        journal.recordEntity(action, ENTITY, before, Optional.of(entity(2)));
        journal.recordEntity(action, ENTITY, Optional.of(entity(2)), Optional.of(entity(3)));
        assertEquals(java.util.Set.of(entity(1), entity(3)), prepared);
        journal.close(action);
        journal.clear();
        assertEquals(java.util.Set.of(entity(1)), prepared);
        references.release(before);
        assertTrue(prepared.isEmpty());
    }

    @Test void entityRedoReplacementKeepsSharedPreparedEndpoint() {
        var prepared = new HashSet<EntityState>(List.of(entity(1), entity(2)));
        var references = new LiveEntityStateReferences(prepared::remove);
        var journal = new LiveActionJournal(LiveActionJournal.Limits.DEFAULT,
                ignored -> { }, ignored -> { }, references);
        UUID first = journal.begin(PLAYER_A);
        journal.recordEntity(first, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(first);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        prepared.add(entity(3));
        UUID second = journal.begin(PLAYER_A);
        journal.recordEntity(second, ENTITY, Optional.of(entity(1)), Optional.of(entity(3)));
        journal.close(second);
        assertEquals(java.util.Set.of(entity(1), entity(3)), prepared);
        journal.clear();
        assertTrue(prepared.isEmpty());
    }

    @Test void rejectedEntityRecordsReleaseUnownedNativeInputs() {
        var prepared = new HashSet<EntityState>(List.of(entity(1), entity(2)));
        var references = new LiveEntityStateReferences(prepared::remove);
        var journal = new LiveActionJournal(new LiveActionJournal.Limits(64, 32, 128, 256),
                ignored -> { }, ignored -> { }, references);
        UUID action = journal.begin(PLAYER_A);
        journal.recordEntity(action, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        assertTrue(prepared.isEmpty());
        prepared.add(entity(3));
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(entity(3)));
        assertTrue(prepared.isEmpty());
        journal.close(action);
    }

    @Test void releasesCoalescedAndRevertedStatesWhileKeepingUndoEndpoints() {
        var prepared = new HashSet<BlockSnapshot>();
        var journal = new LiveActionJournal(LiveActionJournal.Limits.DEFAULT, ignored -> { }, prepared::remove);
        UUID action = journal.begin(PLAYER_A);
        prepared.addAll(List.of(block("stone"), block("dirt"), block("gold_block")));
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.record(action, POSITION, block("dirt"), block("gold_block"));
        assertEquals(java.util.Set.of(block("stone"), block("gold_block")), prepared);
        journal.record(action, POSITION, block("gold_block"), block("stone"));
        assertTrue(prepared.isEmpty());
        assertTrue(!journal.close(action));
    }

    @Test void sharedPreparedStateSurvivesUntilItsFinalActionLeavesHistory() {
        var prepared = new HashSet<BlockSnapshot>();
        var journal = new LiveActionJournal(LiveActionJournal.Limits.DEFAULT, ignored -> { }, prepared::remove);
        prepared.addAll(List.of(block("stone"), block("dirt"), block("gold_block")));
        UUID first = journal.begin(PLAYER_A);
        journal.record(first, POSITION, block("stone"), block("dirt"));
        UUID second = journal.begin(PLAYER_B);
        journal.record(second, new BlockPosition(8, 4, 5), block("stone"), block("gold_block"));
        journal.close(second);
        journal.makeUnavailable(first, "discard first");
        journal.close(first);
        assertEquals(java.util.Set.of(block("stone"), block("gold_block")), prepared);
        journal.clear();
        assertTrue(prepared.isEmpty());
    }

    @Test void newRecordPinsSharedEndpointsBeforeClearingRedo() {
        var prepared = new HashSet<BlockSnapshot>();
        var journal = new LiveActionJournal(LiveActionJournal.Limits.DEFAULT, ignored -> { }, prepared::remove);
        prepared.addAll(List.of(block("air"), block("stone")));
        add(journal, 1);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        prepared.add(block("gold_block"));
        UUID next = journal.begin(PLAYER_A);
        journal.record(next, POSITION, block("stone"), block("gold_block"));
        journal.close(next);
        assertEquals(java.util.Set.of(block("stone"), block("gold_block")), prepared);
        assertEquals(block("stone"), journal.prepareUndo(PLAYER_A).orElseThrow().replacement().get(POSITION));
    }

    @Test void rejectedAndLateRecordsCannotRetainPreparedStates() {
        var prepared = new HashSet<BlockSnapshot>();
        var journal = new LiveActionJournal(new LiveActionJournal.Limits(64, 32, 128, 256),
                ignored -> { }, prepared::remove);
        UUID action = journal.begin(PLAYER_A);
        prepared.addAll(List.of(block("stone"), block("gold_block")));
        journal.record(action, POSITION, block("stone"), block("gold_block"));
        assertTrue(prepared.isEmpty());
        prepared.addAll(List.of(block("dirt"), block("air")));
        journal.record(action, POSITION, block("dirt"), block("air"));
        assertTrue(prepared.isEmpty());
        journal.close(action);
    }

    @Test void actionEvictionDoesNotReleaseEndpointsPreparedForNewRecord() {
        var prepared = new HashSet<BlockSnapshot>();
        var journal = new LiveActionJournal(new LiveActionJournal.Limits(64, 1000, 1000, 600),
                ignored -> { }, prepared::remove);
        prepared.addAll(List.of(block("air"), block("stone")));
        add(journal, 1);
        UUID second = journal.begin(PLAYER_B);
        journal.record(second, POSITION, block("air"), block("stone"));
        journal.close(second);
        assertEquals(java.util.Set.of(block("air"), block("stone")), prepared);
        assertTrue(journal.prepareUndo(PLAYER_A).isEmpty());
        journal.clear();
        assertTrue(prepared.isEmpty());
    }

    @Test void streamedGroupWaitsForEveryProducerAndSurvivesIndependentActionClosure() {
        var journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER_A);
        journal.holdForMoreChanges(first);
        journal.record(first, POSITION, block("stone"), block("dirt"));
        journal.close(first);
        UUID second = journal.begin(PLAYER_A);
        journal.holdForMoreChanges(second);
        journal.mergeGroups(first, second);
        journal.record(second, POSITION, block("dirt"), block("gold_block"));
        journal.close(second);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
        journal.finishChanges(first);
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
        journal.finishChanges(second);
        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(block("gold_block"), undo.expected().get(POSITION));
        assertEquals(block("stone"), undo.replacement().get(POSITION));
        journal.clear();
        journal.finishChanges(first);
        journal.finishChanges(second);
        assertTrue(journal.prepareUndo(PLAYER_A).isEmpty());
    }

    private static final UUID PLAYER_A = new UUID(0, 1);
    private static final UUID PLAYER_B = new UUID(0, 2);
    private static final UUID ENTITY = new UUID(0, 3);
    private static final BlockPosition POSITION = new BlockPosition(3, 4, 5);

    @Test
    void keepsFirstOldAndLatestFinalForExactUndoRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.record(action, POSITION, block("dirt"), block("gold_block"));
        assertTrue(journal.close(action));

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(block("gold_block"), undo.expected().get(POSITION));
        assertEquals(block("stone"), undo.replacement().get(POSITION));
        journal.complete(undo);

        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(block("stone"), redo.expected().get(POSITION));
        assertEquals(block("gold_block"), redo.replacement().get(POSITION));
    }

    @Test
    void checkpointMovesAsOneConstantSizeStackEntry() {
        LiveActionJournal journal = new LiveActionJournal();
        var checkpoint = new WorldActionCheckpoint(
                new BranchName("main"), commit('2'), commit('1'), new BranchRef(
                        new BranchName("hidden/session-undo/checkpoint"), commit('2'), 0),
                WorldActionScope.empty());

        UUID action = journal.pushCheckpoint(PLAYER_A, checkpoint);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(action, undo.actionId());
        assertEquals(Optional.of(checkpoint), undo.checkpoint());
        assertTrue(undo.expected().isEmpty());
        assertTrue(undo.expectedEntities().isEmpty());
        journal.complete(undo);
        assertEquals(Optional.of(checkpoint),
                journal.prepareRedo(PLAYER_A).orElseThrow().checkpoint());
    }

    @Test
    void capturesPortablePayloadSeparatelyFromTheUndoRedoCursor() {
        var journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("gold_block"));
        journal.close(action);
        var checkpoint = checkpoint('1');
        UUID restore = journal.pushCheckpoint(PLAYER_A, checkpoint);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        var index = journal.snapshotIndex();
        var exported = journal.snapshotAction(index.actions().getFirst());
        assertEquals(List.of(action), index.players().get(PLAYER_A).undo());
        assertEquals(List.of(restore), index.players().get(PLAYER_A).redo());
        assertEquals(Optional.of(checkpoint), index.actions().getLast().checkpoint());
        assertEquals(block("stone"), exported.payload().blocks().get(POSITION).before());
        assertThrows(UnsupportedOperationException.class, () -> exported.payload().blocks().clear());

        journal.record(action, POSITION, block("gold_block"), block("diamond_block"));
        assertEquals(block("gold_block"), exported.payload().blocks().get(POSITION).after());
        assertTrue(journal.revision() > index.revision());
        assertThrows(java.util.ConcurrentModificationException.class,
                () -> journal.snapshotAction(index.actions().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new WorldActionIndex(
                index.revision(), index.nextSequence(), index.unsafeBeforeSequence(), index.actions(),
                Map.of(PLAYER_B, index.players().get(PLAYER_A))));
    }

    @Test
    void portableIndexKeepsProducerCompletionWithoutRewritingThePayload() {
        var journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("gold_block"));
        journal.holdForMoreChanges(action);
        journal.close(action);
        var pending = journal.snapshotIndex().actions().getFirst();
        assertTrue(pending.pending());
        journal.finishChanges(action);
        var finished = journal.snapshotIndex().actions().getFirst();
        assertTrue(!finished.pending());
        assertEquals(pending.revision(), finished.revision());
    }

    @Test
    void invalidCheckpointCannotLeaveAnOpenAction() {
        LiveActionJournal journal = new LiveActionJournal();

        assertThrows(NullPointerException.class,
                () -> journal.pushCheckpoint(PLAYER_A, null));
        assertThrows(IllegalArgumentException.class, () -> new WorldActionCheckpoint(
                new BranchName("main"), commit('1'), commit('2'),
                new BranchRef(new BranchName("hidden/session-undo/invalid"), commit('3'), 0),
                WorldActionScope.empty()));

        UUID action = journal.begin(PLAYER_A);
        assertTrue(!journal.close(action));
    }

    @Test
    void releasesCheckpointOnlyWhenItLeavesTheSessionHistory() {
        var released = new java.util.ArrayList<WorldActionCheckpoint>();
        LiveActionJournal journal = new LiveActionJournal(released::add);
        var first = checkpoint('1');
        var second = checkpoint('2');
        journal.pushCheckpoint(PLAYER_A, first);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        journal.complete(undo);
        assertTrue(released.isEmpty());

        journal.pushCheckpoint(PLAYER_A, second);
        assertEquals(List.of(first), released);
        journal.clear();
        assertEquals(List.of(first, second), released);
    }

    @Test
    void traversesBuilderAndReplacementActionsOnOneUndoRedoStack() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID axiom = journal.begin(PLAYER_A);
        journal.record(axiom, POSITION, block("stone"), block("gold_block"));
        journal.close(axiom);
        var checkpoint = checkpoint('1');
        UUID restore = journal.pushCheckpoint(PLAYER_A, checkpoint);

        var undoRestore = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(restore, undoRestore.actionId());
        assertEquals(Optional.of(checkpoint), undoRestore.checkpoint());
        journal.complete(undoRestore);

        var undoAxiom = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(axiom, undoAxiom.actionId());
        assertEquals(block("gold_block"), undoAxiom.expected().get(POSITION));
        assertEquals(block("stone"), undoAxiom.replacement().get(POSITION));
        journal.complete(undoAxiom);
        assertTrue(journal.prepareUndo(PLAYER_A).isEmpty());

        var redoAxiom = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(axiom, redoAxiom.actionId());
        assertEquals(block("stone"), redoAxiom.expected().get(POSITION));
        assertEquals(block("gold_block"), redoAxiom.replacement().get(POSITION));
        journal.complete(redoAxiom);
        var redoRestore = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(restore, redoRestore.actionId());
        assertEquals(Optional.of(checkpoint),
                redoRestore.checkpoint());
        journal.complete(redoRestore);
        assertTrue(journal.prepareRedo(PLAYER_A).isEmpty());
    }

    @Test
    void checkpointRejectsLaterOverlapButAllowsDisjointCellsInTheSameSection() {
        var journal = new LiveActionJournal();
        journal.pushCheckpoint(PLAYER_A, scopedCheckpoint(POSITION));
        var disjoint = new BlockPosition(POSITION.x() + 1, POSITION.y(), POSITION.z());
        UUID other = journal.begin(PLAYER_B);
        journal.record(other, disjoint, block("air"), block("diamond_block"));
        journal.close(other);
        assertTrue(journal.prepareUndo(PLAYER_A).isPresent());

        for (String[] change : List.of(new String[] {"gold_block", "dirt"},
                new String[] {"dirt", "gold_block"})) {
            other = journal.begin(PLAYER_B);
            journal.record(other, POSITION, block(change[0]), block(change[1]));
            journal.close(other);
        }
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void newerCheckpointProtectsOlderBuildersUntilItIsUndone(boolean switchesContext) {
        var journal = new LiveActionJournal();
        UUID older = journal.begin(PLAYER_A);
        journal.record(older, POSITION, block("stone"), block("gold_block"));
        journal.close(older);
        var checkpoint = scopedCheckpoint(POSITION);
        if (switchesContext) checkpoint = checkpoint('1').withContext(new io.github.lumi.domain.model.WorldActionContext.Change(
                new io.github.lumi.domain.model.WorldActionContext(PLAYER_A, new BranchName("main")),
                new io.github.lumi.domain.model.WorldActionContext(PLAYER_A, new BranchName("next"))));
        journal.pushCheckpoint(PLAYER_B, checkpoint);

        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
        journal.complete(journal.prepareUndo(PLAYER_B).orElseThrow());
        assertEquals(older, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void checkpointPairsUseTheirChangedCellsIncludingNegativeCoordinates() {
        var journal = new LiveActionJournal();
        var negative = new BlockPosition(-1, -1, -1);
        assertTrue(scopedCheckpoint(negative).scope().intersects(
                java.util.Set.of(negative), java.util.Set.of()));
        journal.pushCheckpoint(PLAYER_A, scopedCheckpoint(negative));
        journal.pushCheckpoint(PLAYER_B, scopedCheckpoint(new BlockPosition(-2, -1, -1)));
        assertTrue(journal.prepareUndo(PLAYER_A).isPresent());
        journal.pushCheckpoint(PLAYER_B, scopedCheckpoint(negative));
        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    private static WorldActionCheckpoint scopedCheckpoint(BlockPosition position) {
        var key = new SectionKey(position.x() >> 4, position.y() >> 4, position.z() >> 4);
        var before = new SectionBlob(java.util.Collections.nCopies(SectionBlob.BLOCK_COUNT,
                "minecraft:stone"), Map.of());
        var states = new java.util.ArrayList<>(before.blockStates());
        int index = (position.y() & 15) << 8 | (position.z() & 15) << 4 | position.x() & 15;
        states.set(index, "minecraft:gold_block");
        var scope = WorldActionScope.between(Map.of(key, before),
                Map.of(key, new SectionBlob(states, Map.of())), Map.of(), Map.of());
        var checkpoint = checkpoint('1');
        return new WorldActionCheckpoint(checkpoint.branch(), checkpoint.before(), checkpoint.after(),
                checkpoint.retainedRef(), scope);
    }

    @Test
    void planPreservesMutationOrderAndCannotBeModified() {
        BlockPosition nested = new BlockPosition(6, 7, 8);
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("tnt"), block("air"));
        journal.record(action, nested, block("air"), block("redstone_block"));
        journal.close(action);

        var plan = journal.prepareUndo(PLAYER_A).orElseThrow();

        assertEquals(List.of(POSITION, nested), List.copyOf(plan.expected().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.expected().put(POSITION, block("diamond_block")));
        journal.complete(plan);
        assertEquals(List.of(POSITION, nested), List.copyOf(
                journal.prepareRedo(PLAYER_A).orElseThrow().expected().keySet()));
    }

    @Test
    void undoAppliesGroupedActionsNewestFirst() {
        BlockPosition olderPosition = new BlockPosition(1, 0, 0);
        BlockPosition newerPosition = new BlockPosition(6, 7, 8);
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = add(journal, 1);
        UUID newer = journal.begin(PLAYER_A);
        journal.record(newer, newerPosition, block("air"), block("redstone_block"));
        journal.close(newer);
        journal.mergeGroups(older, newer);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();

        assertEquals(List.of(newerPosition, olderPosition),
                List.copyOf(undo.expected().keySet()));
        journal.complete(undo);
        assertEquals(List.of(olderPosition, newerPosition), List.copyOf(
                journal.prepareRedo(PLAYER_A).orElseThrow().expected().keySet()));
    }

    @Test
    void exposesPlayerOwnershipForCausalConsequences() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);

        assertEquals(Optional.of(PLAYER_A), journal.owner(action));
        assertEquals(Optional.empty(), journal.owner(UUID.randomUUID()));
    }

    @Test
    void summarizesOneActionWithoutExposingWorldContent() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.retain(action);

        var summary = journal.summary(action);

        assertEquals(action, summary.actionId());
        assertEquals(PLAYER_A, summary.player());
        assertEquals(1, summary.blocks());
        assertEquals(0, summary.entities());
        assertEquals(1, summary.delayedReferences());
    }

    @Test
    void refusesWholeUndoAfterNewerPlayerOverlapsAnyPosition() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER_A);
        journal.record(first, POSITION, block("stone"), block("dirt"));
        journal.close(first);
        UUID newer = journal.begin(PLAYER_B);
        journal.record(newer, POSITION, block("dirt"), block("gold_block"));
        journal.close(newer);

        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    @Test
    void lateChangeKeepsOverlappingNewerActionFirst() {
        LiveActionJournal journal = new LiveActionJournal();
        BlockPosition delayed = new BlockPosition(8, 4, 5);
        UUID older = journal.begin(PLAYER_A);
        journal.record(older, POSITION, block("stone"), block("dirt"));
        journal.record(older, delayed, block("stone"), block("dirt"));
        journal.recordEntity(older, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.retain(older);
        journal.close(older);
        UUID newer = journal.begin(PLAYER_A);
        journal.record(newer, POSITION, block("dirt"), block("gold_block"));
        journal.close(newer);

        journal.record(older, delayed, block("dirt"), block("air"));
        journal.recordEntity(older, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.release(older);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(newer, undo.actionId());
        journal.complete(undo);
        var olderUndo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(older, olderUndo.actionId());
        assertEquals(block("air"), olderUndo.expected().get(delayed));
        assertEquals(Optional.of(entity(2)), olderUndo.expectedEntities().get(ENTITY));
        journal.complete(olderUndo);
        journal.complete(journal.prepareRedo(PLAYER_A).orElseThrow());
        assertEquals(newer, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void mergingOlderGroupsKeepsLaterOverlappingActionFirst() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        UUID second = add(journal, 2);
        UUID later = journal.begin(PLAYER_A);
        journal.record(later, new BlockPosition(1, 0, 0), block("stone"), block("gold_block"));
        journal.close(later);

        journal.mergeGroups(first, second);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(later, undo.actionId());
        journal.complete(undo);
        assertEquals(List.of(first, second), journal.prepareUndo(PLAYER_A).orElseThrow().actionIds());
    }

    @Test
    void foldsLateOverlapIntoNewerVisibleAction() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = journal.begin(PLAYER_A);
        journal.record(older, POSITION, block("stone"), block("dirt"));
        journal.retain(older);
        journal.close(older);
        UUID newer = journal.begin(PLAYER_A);
        journal.record(newer, POSITION, block("dirt"), block("redstone_block"));
        journal.close(newer);

        journal.record(older, POSITION, block("redstone_block"), block("air"));
        journal.release(older);

        var newerUndo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(newer, newerUndo.actionId());
        assertEquals(block("air"), newerUndo.expected().get(POSITION));
        assertEquals(block("dirt"), newerUndo.replacement().get(POSITION));
        journal.complete(newerUndo);
        assertEquals(older, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void foldsLateEntityOverlapIntoNewerVisibleAction() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = journal.begin(PLAYER_A);
        journal.recordEntity(
                older, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.retain(older);
        journal.close(older);
        UUID newer = journal.begin(PLAYER_A);
        journal.recordEntity(
                newer, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(newer);

        UUID effective = journal.recordEntity(
                older, ENTITY, Optional.of(entity(2)), Optional.empty());
        journal.release(older);

        assertEquals(newer, effective);
        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(Optional.empty(), undo.expectedEntities().get(ENTITY));
        assertEquals(Optional.of(entity(1)),
                undo.replacementEntities().get(ENTITY));
    }

    @Test
    void keepsCrossPlayerLateEffectsInTheirCausalAction() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = journal.begin(PLAYER_A);
        journal.record(older, POSITION, block("stone"), block("dirt"));
        journal.retain(older);
        journal.close(older);
        UUID newer = journal.begin(PLAYER_B);
        journal.record(newer, POSITION, block("dirt"), block("gold_block"));
        journal.close(newer);

        UUID effective = journal.record(
                older, POSITION, block("gold_block"), block("air"));
        journal.release(older);

        assertEquals(older, effective);
        assertThrows(IllegalStateException.class,
                () -> journal.prepareUndo(PLAYER_A));
    }

    @Test
    void closingAnOpenGroupMemberKeepsTheGroupContiguous() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        UUID unrelated = add(journal, 2);
        UUID second = journal.begin(PLAYER_A);
        journal.record(second, new BlockPosition(3, 0, 0), block("air"), block("stone"));
        journal.mergeGroups(first, second);
        journal.close(second);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(List.of(first, second), undo.actionIds());
        journal.complete(undo);
        assertEquals(unrelated, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void groupedActionsPrepareAndCompleteAsOneStackEntry() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        UUID unrelated = add(journal, 2);
        UUID second = add(journal, 3);

        journal.mergeGroups(first, second);

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(List.of(first, second), undo.actionIds());
        assertEquals(2, undo.expected().size());
        journal.complete(undo);
        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(List.of(first, second), redo.actionIds());
        journal.complete(redo);
        assertEquals(List.of(first, second),
                journal.prepareUndo(PLAYER_A).orElseThrow().actionIds());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        assertEquals(unrelated,
                journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void refreshesVisibleEntityEndpointThroughGroupRepresentative() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID entityAction = journal.begin(PLAYER_A);
        journal.recordEntity(
                entityAction, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.close(entityAction);
        UUID representative = add(journal, 2);
        journal.mergeGroups(entityAction, representative);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        journal.refreshEntityBefore(
                representative, ENTITY, Optional.of(entity(2)));

        assertEquals(Optional.of(entity(2)),
                journal.prepareRedo(PLAYER_A).orElseThrow()
                        .expectedEntities().get(ENTITY));
    }

    @Test
    void actionLimitCountsAndEvictsWholeGroups() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(2, 1_000, 4_000, 8_000));
        UUID first = add(journal, 1);
        UUID second = add(journal, 2);
        journal.mergeGroups(first, second);
        UUID third = add(journal, 3);
        UUID fourth = add(journal, 4);

        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        assertEquals(third,
                journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        assertTrue(journal.prepareUndo(PLAYER_A).isEmpty());
        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(third, redo.actionId());
        journal.complete(redo);
        assertEquals(fourth,
                journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void causalGroupMayExceedTheMemberCountLimit() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 0);

        for (int index = 1; index <= 65; index++) {
            journal.mergeGroups(first, add(journal, index));
        }

        assertEquals(66,
                journal.prepareUndo(PLAYER_A).orElseThrow().actionIds().size());
    }

    @Test
    void undoRedoTraversesOverlappingAppliedActions() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID placement = journal.begin(PLAYER_A);
        journal.record(placement, POSITION, block("air"), block("tnt"));
        journal.recordEntity(
                placement, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.close(placement);
        UUID explosion = journal.begin(PLAYER_A);
        journal.record(explosion, POSITION, block("tnt"), block("air"));
        journal.recordEntity(
                explosion, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(explosion);

        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        assertEquals(placement, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        journal.complete(journal.prepareRedo(PLAYER_A).orElseThrow());
        assertEquals(explosion, journal.prepareRedo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void newDirectActionClearsOnlyThatPlayersRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, POSITION, block("stone"), block("dirt"));
        journal.close(action);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        UUID next = journal.begin(PLAYER_A);
        journal.record(next, new BlockPosition(8, 9, 10), block("air"), block("stone"));
        journal.close(next);

        assertEquals(Optional.empty(), journal.prepareRedo(PLAYER_A));
    }

    @Test
    void clearingRedoKeepsOlderUnrelatedUndoAvailable() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = add(journal, 1);
        add(journal, 2);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());
        add(journal, 3);
        journal.complete(journal.prepareUndo(PLAYER_A).orElseThrow());

        assertEquals(first, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void emptyNewerActionDoesNotInvalidateOlderUndo() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID older = add(journal, 1);
        UUID empty = journal.begin(PLAYER_A);

        assertTrue(!journal.close(empty));
        assertEquals(older, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
    }

    @Test
    void oversizedActionIsUnavailableWithAReason() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(64, 32, 128, 256));
        UUID action = journal.begin(PLAYER_A);

        journal.record(action, POSITION, block("stone"), block("gold_block"));

        assertTrue(!journal.close(action));
        assertTrue(journal.lastUnavailableReason(PLAYER_A).orElseThrow().contains("limit"));
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
        assertEquals(Optional.empty(), journal.owner(action));
    }

    @Test
    void externalCaptureLimitMakesOpenActionUnavailable() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);

        journal.makeUnavailable(action, "Axiom edit exceeded its capture limit");

        assertTrue(!journal.close(action));
        assertTrue(journal.lastUnavailableReason(PLAYER_A).orElseThrow().contains("Axiom"));
        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
    }

    @Test
    void evictsOldestClosedActionAtPlayerCountLimit() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(2, 1_000, 4_000, 8_000));
        UUID first = add(journal, 1);
        UUID second = add(journal, 2);
        UUID third = add(journal, 3);

        var newest = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(third, newest.actionId());
        journal.complete(newest);
        var remaining = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(second, remaining.actionId());
        assertTrue(!remaining.actionId().equals(first));
    }

    @Test
    void retainedActionSurvivesCountPressureUntilReleased() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(1, 1_000, 4_000, 8_000));
        UUID first = journal.begin(PLAYER_A);
        journal.recordEntity(first, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.retain(first);
        journal.retain(first);
        journal.close(first);

        UUID second = add(journal, 2);

        assertEquals(second, journal.prepareUndo(PLAYER_A).orElseThrow().actionId());
        assertEquals(Optional.of(PLAYER_A), journal.owner(first));
        journal.recordEntity(first, ENTITY, Optional.of(entity(1)), Optional.empty());
        journal.release(first);
        assertEquals(Optional.of(PLAYER_A), journal.owner(first));
        assertEquals(Optional.of(PLAYER_A), journal.owner(second));
        journal.release(first);
        assertEquals(Optional.empty(), journal.owner(first));
    }

    @Test
    void dimensionByteLimitEvictsOldestClosedPlayerAction() {
        LiveActionJournal journal = new LiveActionJournal(
                new LiveActionJournal.Limits(64, 1_000, 1_000, 600));
        add(journal, 1);
        UUID newer = journal.begin(PLAYER_B);
        journal.record(newer, new BlockPosition(2, 0, 0), block("air"), block("stone"));
        journal.close(newer);

        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
        assertEquals(newer, journal.prepareUndo(PLAYER_B).orElseThrow().actionId());
    }

    @Test
    void causalReferenceKeepsEmptyClosedActionUntilReleased() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.retain(action);

        assertTrue(journal.close(action));
        assertTrue(journal.prepareUndo(PLAYER_A).isPresent());
        journal.release(action);

        assertEquals(Optional.empty(), journal.prepareUndo(PLAYER_A));
    }

    @Test
    void delayedChangeSurvivesAfterCausalReferenceReleases() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(PLAYER_A);
        journal.retain(action);
        journal.close(action);
        journal.record(action, POSITION, block("air"), block("stone"));
        journal.release(action);

        assertEquals(block("stone"),
                journal.prepareUndo(PLAYER_A).orElseThrow().expected().get(POSITION));
    }

    @Test
    void keepsFirstOldAndLatestFinalEntityForExactUndoRedo() {
        LiveActionJournal journal = new LiveActionJournal();
        EntityState first = entity(1);
        EntityState latest = entity(2);
        UUID action = journal.begin(PLAYER_A);
        journal.recordEntity(action, ENTITY, Optional.empty(), Optional.of(first));
        journal.recordEntity(action, ENTITY, Optional.of(first), Optional.of(latest));
        assertTrue(journal.close(action));

        var undo = journal.prepareUndo(PLAYER_A).orElseThrow();
        assertEquals(Optional.of(latest), undo.expectedEntities().get(ENTITY));
        assertEquals(Optional.empty(), undo.replacementEntities().get(ENTITY));
        journal.complete(undo);

        var redo = journal.prepareRedo(PLAYER_A).orElseThrow();
        assertEquals(Optional.empty(), redo.expectedEntities().get(ENTITY));
        assertEquals(Optional.of(latest), redo.replacementEntities().get(ENTITY));
    }

    @Test
    void refusesWholeUndoAfterNewerPlayerOverlapsEntity() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID first = journal.begin(PLAYER_A);
        journal.recordEntity(first, ENTITY, Optional.empty(), Optional.of(entity(1)));
        journal.close(first);
        UUID newer = journal.begin(PLAYER_B);
        journal.recordEntity(newer, ENTITY, Optional.of(entity(1)), Optional.of(entity(2)));
        journal.close(newer);

        assertThrows(IllegalStateException.class, () -> journal.prepareUndo(PLAYER_A));
    }

    private static UUID add(LiveActionJournal journal, int x) {
        UUID action = journal.begin(PLAYER_A);
        journal.record(action, new BlockPosition(x, 0, 0), block("air"), block("stone"));
        journal.close(action);
        return action;
    }

    private static WorldActionCheckpoint checkpoint(char value) {
        CommitId dirty = commit(value);
        return new WorldActionCheckpoint(
                new BranchName("main"), dirty, commit('0'), new BranchRef(
                        new BranchName("hidden/session-undo/" + value), dirty, 0), WorldActionScope.empty());
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }

    private static EntityState entity(int state) {
        return new EntityState(
                ENTITY, "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) state}));
    }

    private static CommitId commit(char value) {
        return new CommitId(new ObjectId(String.valueOf(value).repeat(64)));
    }
}
