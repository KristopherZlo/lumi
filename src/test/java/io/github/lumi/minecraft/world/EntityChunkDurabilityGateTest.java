package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityChunkDurabilityGateTest {
    @TempDir Path repositoryRoot;

    @Test
    void blocksChangedEntitiesUntilOneOriginAndLatestDirtyGenerationAreDurable()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate gate = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey key = new EntityChunkKey(2, -3);
        EntityChunkBlob original = entities(1);
        EntityChunkBlob changed = entities(2);

        gate.rememberLoaded(key, original);

        assertFalse(gate.permitStore(key, changed));
        assertFalse(gate.permitStore(key, changed));
        assertEquals(1L, mutations.snapshot().generations().get(key));

        background.runNext();
        background.runNext();

        assertTrue(gate.permitStore(key, changed));
        assertTrue(new OriginStore(repositoryRoot).read(key).isPresent());
    }

    @Test
    void treatsFirstObservedFreshChunkAsItsBaseline() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate gate = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey key = new EntityChunkKey(0, 0);

        assertTrue(gate.permitStore(key, entities(1)));
        assertTrue(mutations.snapshot().generations().isEmpty());
        assertEquals(0, background.size());
    }

    @Test
    void saveSweepCanObserveTrackedChunkWithoutVanillaStore() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate gate = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey key = new EntityChunkKey(4, 5);
        gate.rememberLoaded(key, entities(1));

        gate.observeCurrent(key, entities(2));

        assertEquals(Set.of(key), gate.trackedKeys());
        assertEquals(1L, mutations.snapshot().generations().get(key));
        assertFalse(mutations.isDurable(mutations.snapshot()));
        background.runNext();
        background.runNext();
        assertTrue(mutations.isDurable(mutations.snapshot()));
    }

    @Test
    void observesReturnToBaselineAfterIntermediateStateWasSaved() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate gate = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey key = new EntityChunkKey(0, 0);
        EntityChunkBlob original = entities(1);

        gate.rememberLoaded(key, original);
        gate.observeCurrent(key, entities(2));
        mutations.clear(mutations.snapshot());

        gate.observeCurrent(key, original);

        assertEquals(1L, mutations.snapshot().generations().get(key));
    }

    private static EntityChunkBlob entities(int marker) {
        return new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 1), "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) marker}))));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private int size() { return tasks.size(); }
        private void runNext() { tasks.remove().run(); }
    }
}
