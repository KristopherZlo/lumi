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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreBaselineReconcilerTest {
    @TempDir Path repositoryRoot;

    @Test
    void verifiedTargetBecomesTheNewRuntimeBaselineWithoutPendingWork() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        EntityChunkDurabilityGate entities = new EntityChunkDurabilityGate(mutations);
        BlockEntityBaselineStore blockEntities = new BlockEntityBaselineStore();
        EntityChunkKey entityKey = new EntityChunkKey(2, 3);
        EntityChunkKey storageOnlyKey = new EntityChunkKey(8, 9);
        SectionKey sectionKey = new SectionKey(2, 4, 3);
        EntityChunkBlob original = entities(1);
        EntityChunkBlob target = entities(2);
        entities.rememberLoaded(entityKey, original);
        blockEntities.remember(sectionKey, Map.of(0, new CanonicalNbt(new byte[] {1})));
        var state = new WorldStateApply.State(
                Map.of(sectionKey, section()),
                Map.of(entityKey, target, storageOnlyKey, target));

        new RestoreBaselineReconciler(entities, blockEntities).restored(state);

        assertFalse(blockEntities.contains(sectionKey));
        assertEquals(Set.of(entityKey), entities.trackedKeys());
        assertTrue(entities.permitStore(entityKey, target));
        assertTrue(mutations.snapshot().generations().isEmpty());
        assertTrue(background.isEmpty());
    }

    private static EntityChunkBlob entities(int marker) {
        return new EntityChunkBlob(List.of(new EntityState(
                new UUID(0, 1), "minecraft:armor_stand",
                new CanonicalNbt(new byte[] {(byte) marker}))));
    }

    private static SectionBlob section() {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private boolean isEmpty() { return tasks.isEmpty(); }
    }
}
