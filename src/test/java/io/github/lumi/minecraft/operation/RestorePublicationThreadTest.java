package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.ChunkDurabilityRetention;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestorePublicationThreadTest {
    @TempDir Path root;

    @Test
    void pendingAndRecoveryTrackingRetainChunksOnTheServerThread() throws Exception {
        Thread server = Thread.currentThread();
        var writes = new ConcurrentLinkedQueue<Runnable>();
        var retains = new AtomicInteger();
        var key = new SectionKey(0, 0, 0);
        var air = new SectionBlob(Collections.nCopies(4096, "minecraft:air"), Map.of());
        var expected = new BranchRef(new BranchName("main"), id('1'), 0);
        for (boolean recovery : new boolean[] {false, true}) {
            Path repository = root.resolve(Boolean.toString(recovery));
            var objects = new WorldObjectRepository(repository);
            var origins = new OriginStore(repository);
            origins.register(key, objects.write(air));
            var index = new WorkingIndexRepository(repository);
            var tracker = MutationDurabilityTracker.open(objects, origins, index, writes::add,
                    new ChunkDurabilityRetention() {
                        @Override public void retain(int x, int z) {
                            if (Thread.currentThread() != server) {
                                throw new IllegalStateException("Retention requires the server thread");
                            }
                            retains.incrementAndGet();
                        }
                        @Override public void release(int x, int z) { }
                    });
            RestorePublication publication = recovery
                    ? new WorkingIndexRecoveryPublication(tracker, Optional.empty(),
                            WorkingIndexRecoveryPublication.TargetAction.RESTORE)
                    : new PendingRestorePublication(tracker);
            var restore = new PreparedRestore(expected, id('2'), Map.of(key, air),
                    Map.of(), Map.of(key, air), Map.of());
            var operation = RestoreOperation.start(restore, new World(writes::add), publication,
                    new OperationJournalRepository(repository), UUID.randomUUID());
            for (int tick = 0; tick < 20 && !operation.isTerminal(); tick++) {
                operation.tick(Long.MAX_VALUE);
                Runnable write;
                while ((write = writes.poll()) != null) {
                    Thread worker = new Thread(write, "publication-test-worker");
                    worker.start();
                    worker.join();
                }
            }
            assertEquals(RestoreStatus.COMPLETE, operation.status());
            assertEquals(1L, index.read().generations().get(key));
            assertTrue(tracker.canPublishChunk(0, 0));
        }
        assertEquals(2, retains.get());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private record World(Executor persistenceExecutor) implements WorldStateApply {
        @Override public PreparedState prepare(State source) { return new Prepared(source); }
        @Override public ApplySession begin(PreparedState source) {
            return new ApplySession() {
                @Override public boolean applyUntil(long deadline) { return true; }
                @Override public Verification verifyUntil(long deadline) { return Verification.VERIFIED; }
                @Override public boolean persistUntil(long deadline) { return true; }
                @Override public boolean repairUntil(long deadline) { return true; }
                @Override public void restartVerification() { }
            };
        }
    }
    private record Prepared(WorldStateApply.State source) implements WorldStateApply.PreparedState { }
}
