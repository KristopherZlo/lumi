package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopedSavePreparationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path repositoryRoot;

    @Test
    void actionCaptureExpandsEntityDependenciesWithoutReadingUnrelatedDirtyKeys() throws Exception {
        var background = new java.util.ArrayDeque<Runnable>();
        var mutations = MutationDurabilityTracker.open(
                new io.github.lumi.storage.repository.WorldObjectRepository(repositoryRoot),
                new io.github.lumi.storage.repository.OriginStore(repositoryRoot),
                new io.github.lumi.storage.repository.WorkingIndexRepository(repositoryRoot), background::add);
        var entities = new EntityChunkDurabilityGate(mutations);
        var section = new SectionKey(-1, 2, -1);
        var source = new EntityChunkKey(-1, -1);
        var old = new EntityChunkKey(2, 3);
        var moved = new EntityChunkKey(4, 5);
        var id = java.util.UUID.randomUUID();
        var bystander = java.util.UUID.randomUUID();
        var discovered = java.util.UUID.randomUUID();
        var blob = new io.github.lumi.domain.model.SectionBlob(
                java.util.Collections.nCopies(4096, "minecraft:barrel"),
                Map.of(0, new io.github.lumi.domain.model.CanonicalNbt(new byte[] {1})));
        for (int i = 0; i < 1000; i++) {
            var key = new SectionKey(i, 0, 100);
            mutations.registerSectionMutation(key, () -> blob);
            mutations.markBuilderMutation(key);
        }
        var pendingBefore = mutations.snapshot();
        var empty = new io.github.lumi.domain.model.EntityChunkBlob(java.util.List.of());
        var primary = new io.github.lumi.domain.model.EntityState(id, "minecraft:pig", new io.github.lumi.domain.model.CanonicalNbt(new byte[] {2}));
        var neighbor = new io.github.lumi.domain.model.EntityState(bystander, "minecraft:pig", new io.github.lumi.domain.model.CanonicalNbt(new byte[] {3}));
        var current = Map.of(source, new io.github.lumi.domain.model.EntityChunkBlob(java.util.List.of(primary, neighbor)),
                old, empty, moved, empty);
        var reads = new java.util.HashMap<io.github.lumi.domain.model.HistoryKey, Integer>();
        WorldStateReader reader = new WorldStateReader() {
            @Override public io.github.lumi.domain.model.SectionBlob read(SectionKey key) {
                assertEquals(section, key);
                reads.merge(key, 1, Integer::sum);
                return blob;
            }
            @Override public io.github.lumi.domain.model.EntityChunkBlob read(EntityChunkKey key) {
                assertTrue(current.containsKey(key));
                reads.merge(key, 1, Integer::sum);
                return current.get(key);
            }
        };
        ChunkLoadAccess access = new ChunkLoadAccess() {
            @Override public java.util.concurrent.CompletableFuture<Void> retain(ChunkCoordinate key) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public boolean isReady(ChunkCoordinate key) { return true; }
            @Override public void release(ChunkCoordinate key) { }
        };
        var preparation = new ActionReplaySavePreparation(reader, mutations, entities, new ChunkLoadSession(access),
                java.util.Set.of(section, source), java.util.Set.of(id),
                (keys, ids) -> Map.of(source, java.util.Set.of(id, discovered), old, java.util.Set.of(id)),
                identity -> java.util.Optional.of(identity.equals(discovered) ? moved : source), background::add);
        try (var session = preparation.begin()) {
            boolean ready = false;
            for (int step = 0; step < 30 && !ready; step++) {
                ready = session.prepareUntil(System.nanoTime() + 1_000_000_000L);
                while (!background.isEmpty()) background.remove().run();
            }
            assertTrue(ready);
            assertEquals(java.util.Set.of(section, source, old, moved), session.finish().generations().keySet());
            try (var capture = new BatchedWorldStateCapture(preparation).begin(session.finish())) {
                assertTrue(capture.captureUntil(Long.MAX_VALUE));
                var captured = capture.finish();
                assertEquals(Map.of(section, blob), captured.sections());
                assertEquals(current, captured.entities());
            }
            assertEquals(Map.of(section, 1, source, 1, old, 1, moved, 1), reads);
            var pendingAfter = mutations.snapshot();
            pendingBefore.generations().forEach((key, generation) -> assertEquals(generation, pendingAfter.generations().get(key)));
            assertEquals(1000, mutations.builderSnapshot().generations().size());
        }
    }

    @Test
    void delegatesDurabilityThenReturnsOnlyIncludedGenerations() throws Exception {
        SectionKey included = new SectionKey(1, 2, 3);
        SectionKey excluded = new SectionKey(4, 5, 6);
        EntityChunkKey entities = new EntityChunkKey(1, 3);
        WorkingIndexSnapshot preview = new WorkingIndexSnapshot(Map.of(
                included, 7L, excluded, 8L));
        SavePreparation source = () -> new SavePreparation.Session() {
            private int calls;

            @Override
            public boolean prepareUntil(long deadlineNanos) {
                return ++calls == 2;
            }

            @Override
            public WorkingIndexSnapshot finish() {
                return new WorkingIndexSnapshot(Map.of(
                        included, 7L, excluded, 8L, entities, 9L));
            }

            @Override
            public WorkingIndexSnapshot previewGenerations() {
                return preview;
            }
        };
        SavePreparation.Session session = new ScopedSavePreparation(
                source, key -> key.equals(included) || key.equals(entities)).begin();

        assertFalse(session.prepareUntil(1));
        assertTrue(session.prepareUntil(2));
        assertEquals(Map.of(included, 7L, entities, 9L), session.finish().generations());
        assertEquals(Map.of(included, 7L),
                session.previewGenerations().generations());
    }
}
