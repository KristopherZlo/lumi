package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Decodes, applies and verifies at most 32 chunks or 128 MiB per section batch. */
final class StreamingPreparedWorldMutationSession implements WorldStateApply.ApplySession {
    static final int MAX_CHUNKS = 32;
    static final long MAX_ESTIMATED_BYTES = 128L * 1024 * 1024;
    private static final long ESTIMATED_SECTION_BYTES =
            2L * SectionBlob.BLOCK_COUNT * 16;

    private final PreparedMinecraftPlanState plan;
    private final MinecraftRestorePreparation preparation;
    private final PreparedWorldAccess world;
    private final Executor background;
    private final Supplier<ChunkLoadSession> chunkLoads;
    private int batchStart;
    private int batchEnd;
    private CompletableFuture<PreparedMinecraftState> preparing;
    private PreparedWorldMutationSession current;
    private Phase phase = Phase.PREPARING;
    private boolean repairAttempted;
    private boolean tailStarted;

    StreamingPreparedWorldMutationSession(
            PreparedMinecraftPlanState plan,
            MinecraftRestorePreparation preparation,
            PreparedWorldAccess world,
            Executor background,
            Supplier<ChunkLoadSession> chunkLoads) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.world = Objects.requireNonNull(world, "world");
        this.background = Objects.requireNonNull(background, "background");
        this.chunkLoads = Objects.requireNonNull(chunkLoads, "chunkLoads");
    }

    @Override
    public boolean applyUntil(long deadlineNanos) throws IOException {
        while (phase != Phase.COMPLETE && System.nanoTime() < deadlineNanos) {
            switch (phase) {
                case PREPARING -> {
                    if (!prepareNext()) {
                        return false;
                    }
                }
                case APPLYING -> {
                    if (current.applyUntil(deadlineNanos)) {
                        phase = Phase.VERIFYING;
                    } else {
                        return false;
                    }
                }
                case VERIFYING -> verifyCurrent(deadlineNanos);
                case REPAIRING -> {
                    if (current.repairUntil(deadlineNanos)) {
                        current.restartVerification();
                        phase = Phase.VERIFYING;
                    } else {
                        return false;
                    }
                }
                case COMPLETE -> { }
            }
        }
        return phase == Phase.COMPLETE;
    }

    private boolean prepareNext() throws IOException {
        if (current != null) {
            current.close();
            current = null;
            batchStart = batchEnd;
            repairAttempted = false;
        }
        if (batchStart < plan.sectionKeys().size()) {
            if (preparing == null) {
                Batch batch = nextBatch();
                batchEnd = batch.end();
                preparing = CompletableFuture.supplyAsync(() -> {
                    try {
                        return preparation.prepareBatch(batch.target(), batch.base());
                    } catch (IOException failed) {
                        throw new CompletionException(failed);
                    }
                }, background);
            }
            if (!preparing.isDone()) {
                return false;
            }
            current = new PreparedWorldMutationSession(
                    claimPrepared(), world, System::nanoTime, chunkLoads.get());
            phase = Phase.APPLYING;
            return true;
        }
        if (!tailStarted) {
            tailStarted = true;
            var source = new WorldStateApply.State(
                    Map.of(), plan.source().entities(), plan.source().playerSpawns());
            var tail = new PreparedMinecraftState(
                    source, Map.of(), plan.entities(), List.of(), plan.entityKeys());
            current = new PreparedWorldMutationSession(
                    tail, world, System::nanoTime, chunkLoads.get());
            phase = Phase.APPLYING;
            return true;
        }
        phase = Phase.COMPLETE;
        return true;
    }

    private PreparedMinecraftState claimPrepared() throws IOException {
        try {
            PreparedMinecraftState result = preparing.join();
            preparing = null;
            return result;
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Cannot prepare Restore batch", cause);
        }
    }

    private Batch nextBatch() {
        List<SectionKey> keys = plan.sectionKeys();
        Map<SectionKey, io.github.lumi.domain.model.SectionBlob> target = new HashMap<>();
        Map<SectionKey, io.github.lumi.domain.model.SectionBlob> base = new HashMap<>();
        int end = batchEnd(keys, batchStart);
        for (int index = batchStart; index < end; index++) {
            SectionKey key = keys.get(index);
            target.put(key, plan.source().sections().get(key));
            base.put(key, plan.base().sections().get(key));
        }
        return new Batch(
                new WorldStateApply.State(target, Map.of()),
                new WorldStateApply.State(base, Map.of()), end);
    }

    static int batchEnd(List<SectionKey> keys, int start) {
        int chunks = 0;
        long bytes = 0;
        SectionKey previous = null;
        int end = start;
        while (end < keys.size()) {
            SectionKey key = keys.get(end);
            boolean newChunk = previous == null || !sameChunk(previous, key);
            long nextBytes = bytes + ESTIMATED_SECTION_BYTES;
            if (end > start && (newChunk && chunks == MAX_CHUNKS
                    || nextBytes > MAX_ESTIMATED_BYTES)) {
                break;
            }
            if (newChunk) {
                chunks++;
            }
            bytes = nextBytes;
            previous = key;
            end++;
        }
        return end;
    }

    private void verifyCurrent(long deadlineNanos) throws IOException {
        WorldStateApply.Verification verification = current.verifyUntil(deadlineNanos);
        if (verification == WorldStateApply.Verification.IN_PROGRESS) {
            return;
        }
        if (verification == WorldStateApply.Verification.VERIFIED) {
            phase = Phase.PREPARING;
            return;
        }
        if (repairAttempted) {
            throw new IOException("Restore batch still mismatched after repair");
        }
        repairAttempted = true;
        phase = Phase.REPAIRING;
    }

    @Override
    public WorldStateApply.Verification verifyUntil(long deadlineNanos) {
        return phase == Phase.COMPLETE
                ? WorldStateApply.Verification.VERIFIED
                : WorldStateApply.Verification.IN_PROGRESS;
    }

    @Override public boolean repairUntil(long deadlineNanos) { return true; }
    @Override public void restartVerification() { }

    @Override
    public void close() {
        if (preparing != null) {
            preparing.cancel(true);
        }
        if (current != null) {
            current.close();
        }
    }

    static boolean sameChunk(SectionKey left, SectionKey right) {
        return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    private record Batch(
            WorldStateApply.State target,
            WorldStateApply.State base,
            int end) { }

    private enum Phase { PREPARING, APPLYING, VERIFYING, REPAIRING, COMPLETE }
}
