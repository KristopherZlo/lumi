package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableSavePreparationTest {
    @TempDir Path repositoryRoot;

    @Test
    void observesLoadedEntitiesThenWaitsForOriginAndIndexDurability() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate entities = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey key = new EntityChunkKey(2, 3);
        entities.rememberLoaded(key, entities(1));
        AtomicLong clock = new AtomicLong();
        WorldStateReader reader = new WorldStateReader() {
            @Override public SectionBlob read(SectionKey ignored) {
                throw new AssertionError("Preparation reads only entity baselines");
            }
            @Override public EntityChunkBlob read(EntityChunkKey ignored) {
                clock.addAndGet(60);
                return entities(2);
            }
        };
        SavePreparation.Session session = new DurableSavePreparation(
                reader, entities, mutations, clock::get).begin();

        assertFalse(session.prepareUntil(50));
        assertEquals(1L, mutations.snapshot().generations().get(key));

        background.runNext();
        background.runNext();

        assertTrue(session.prepareUntil(110));
        assertEquals(mutations.snapshot(), session.finish());
    }

    @Test
    void scopedPreparationDoesNotReadUnrelatedEntityChunks() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate entities = new EntityChunkDurabilityGate(mutations);
        EntityChunkKey included = new EntityChunkKey(2, 3);
        EntityChunkKey excluded = new EntityChunkKey(200, 300);
        entities.rememberLoaded(included, entities(1));
        entities.rememberLoaded(excluded, entities(1));
        var reads = new ArrayList<EntityChunkKey>();
        WorldStateReader reader = new WorldStateReader() {
            @Override public SectionBlob read(SectionKey ignored) {
                throw new AssertionError("Preparation reads only entity baselines");
            }
            @Override public EntityChunkBlob read(EntityChunkKey key) {
                reads.add(key);
                return entities(2);
            }
        };
        SavePreparation.Session session = new DurableSavePreparation(
                reader, entities, mutations, key -> key.equals(included)).begin();

        assertFalse(session.prepareUntil(Long.MAX_VALUE));
        background.runNext();
        background.runNext();
        assertTrue(session.prepareUntil(Long.MAX_VALUE));

        assertEquals(List.of(included), reads);
        assertEquals(java.util.Set.of(included),
                session.finish().generations().keySet());
    }

    private static EntityChunkBlob entities(int marker) {
        return new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 1), "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) marker}))));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private void runNext() { tasks.remove().run(); }
    }
}
