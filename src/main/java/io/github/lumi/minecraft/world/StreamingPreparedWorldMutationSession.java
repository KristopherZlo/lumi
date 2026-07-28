package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Decodes an estimated 128 MiB, then applies and persists it in 32-chunk windows. */
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
    private final RestoreApplyMetrics metrics = new RestoreApplyMetrics();
    private int batchStart;
    private int batchEnd;
    private int slabEnd;
    private int entityBatchStart;
    private int entityBatchEnd;
    private CompletableFuture<PreparedMinecraftState> preparing;
    private PreparedMinecraftState slab;
    private PreparedWorldMutationSession current;
    private BatchKind currentKind;
    private Phase phase = Phase.PREPARING;
    private boolean repairAttempted;
    private boolean spawnsStarted;
    private volatile boolean closed;

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
        if (closed) {
            return false;
        }
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
                case PERSISTING -> {
                    if (current.persistUntil(deadlineNanos)) {
                        phase = Phase.PREPARING;
                    } else {
                        return false;
                    }
                }
                case REPAIRING -> {
                    if (current.repairUntil(deadlineNanos)) {
                        current.restartVerification();
                        phase = Phase.VERIFYING;
                    } else {
                        return false;
                    }
                }
                case LIGHTING -> {
                    if (world.finishLighting()) {
                        phase = Phase.COMPLETE;
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
            if (currentKind == BatchKind.SECTIONS) {
                batchStart = batchEnd;
                if (batchStart == slabEnd) {
                    slab = null;
                }
            } else if (currentKind == BatchKind.ENTITIES) {
                entityBatchStart = entityBatchEnd;
            }
            currentKind = null;
            repairAttempted = false;
        }
        if (batchStart < plan.sectionKeys().size()) {
            if (slab == null) {
                if (preparing == null) {
                    int start = batchStart;
                    slabEnd = slabEnd(plan.sectionKeys(), start);
                    int end = slabEnd;
                    preparing = CompletableFuture.supplyAsync(() -> {
                        try {
                            Batch batch = loadBatch(start, end);
                            return preparation.preparePreflightedBatch(
                                    batch.target(), batch.base(), batch.order(),
                                    () -> closed);
                        } catch (UncheckedIOException failed) {
                            throw new CompletionException(failed.getCause());
                        } catch (IOException failed) {
                            throw new CompletionException(failed);
                        }
                    }, background);
                }
                if (!preparing.isDone()) {
                    return false;
                }
                slab = claimPrepared();
            }
            batchEnd = windowEnd(plan.sectionKeys(), batchStart, slabEnd);
            current = new PreparedWorldMutationSession(
                    nextSectionWindow(), world, System::nanoTime, chunkLoads.get(), metrics);
            currentKind = BatchKind.SECTIONS;
            phase = Phase.APPLYING;
            return true;
        }
        if (entityBatchStart < plan.entityKeys().size()) {
            PreparedMinecraftState entities = nextEntityBatch();
            current = new PreparedWorldMutationSession(
                    entities, world, System::nanoTime, chunkLoads.get(), metrics);
            currentKind = BatchKind.ENTITIES;
            phase = Phase.APPLYING;
            return true;
        }
        if (!spawnsStarted && plan.source().playerSpawnsIncluded()) {
            spawnsStarted = true;
            var source = new WorldStateApply.State(
                    Map.of(), Map.of(), plan.source().playerSpawns());
            current = new PreparedWorldMutationSession(
                    new PreparedMinecraftState(source, Map.of(), Map.of()),
                    world, System::nanoTime, chunkLoads.get(), metrics);
            currentKind = BatchKind.SPAWNS;
            phase = Phase.APPLYING;
            return true;
        }
        phase = Phase.LIGHTING;
        return true;
    }

    private PreparedMinecraftState claimPrepared() throws IOException {
        CompletableFuture<PreparedMinecraftState> pending = preparing;
        preparing = null;
        try {
            return pending.join();
        } catch (CancellationException failed) {
            throw new IOException("Restore preparation was cancelled", failed);
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Cannot prepare Restore batch", cause);
        }
    }

    private Batch loadBatch(int start, int end) {
        List<SectionKey> keys = plan.sectionKeys();
        Map<SectionKey, SectionBlob> target = new LinkedHashMap<>();
        Map<SectionKey, SectionBlob> base = new LinkedHashMap<>();
        for (int index = start; index < end; index++) {
            if (closed) {
                throw new CancellationException("Restore preparation was cancelled");
            }
            SectionKey key = keys.get(index);
            target.put(key, plan.source().sections().get(key));
            base.put(key, plan.base().sections().get(key));
        }
        return new Batch(
                new WorldStateApply.State(target, Map.of()),
                new WorldStateApply.State(base, Map.of()),
                List.copyOf(keys.subList(start, end)));
    }

    private PreparedMinecraftState nextSectionWindow() {
        List<SectionKey> keys = List.copyOf(
                plan.sectionKeys().subList(batchStart, batchEnd));
        Map<SectionKey, SectionBlob> source = new LinkedHashMap<>();
        Map<SectionKey, DecodedSection> sections = new LinkedHashMap<>();
        for (SectionKey key : keys) {
            source.put(key, slab.source().sections().get(key));
            sections.put(key, slab.sections().get(key));
        }
        return new PreparedMinecraftState(
                new WorldStateApply.State(source, Map.of()),
                sections, Map.of(), keys, List.of());
    }

    private PreparedMinecraftState nextEntityBatch() {
        entityBatchEnd = entityBatchEnd(plan.entityKeys().size(), entityBatchStart);
        List<EntityChunkKey> keys = plan.entityKeys().subList(
                entityBatchStart, entityBatchEnd);
        Map<EntityChunkKey, io.github.lumi.domain.model.EntityChunkBlob> source =
                new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        for (EntityChunkKey key : keys) {
            source.put(key, plan.source().entities().get(key));
            decoded.put(key, plan.entities().get(key));
        }
        return new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), source), Map.of(), decoded,
                List.of(), keys);
    }

    static int slabEnd(List<SectionKey> keys, int start) {
        int sections = (int) (MAX_ESTIMATED_BYTES / ESTIMATED_SECTION_BYTES);
        return Math.min(keys.size(), start + sections);
    }

    static int windowEnd(List<SectionKey> keys, int start, int limit) {
        int chunks = 0;
        SectionKey previous = null;
        int end = start;
        int boundedLimit = Math.min(keys.size(), limit);
        while (end < boundedLimit) {
            SectionKey key = keys.get(end);
            boolean newChunk = previous == null || !sameChunk(previous, key);
            if (end > start && newChunk && chunks == MAX_CHUNKS) {
                break;
            }
            if (newChunk) {
                chunks++;
            }
            previous = key;
            end++;
        }
        return end;
    }

    static int entityBatchEnd(int total, int start) {
        return Math.min(total, start + MAX_CHUNKS);
    }

    private void verifyCurrent(long deadlineNanos) throws IOException {
        WorldStateApply.Verification verification = current.verifyUntil(deadlineNanos);
        if (verification == WorldStateApply.Verification.IN_PROGRESS) {
            return;
        }
        if (verification == WorldStateApply.Verification.VERIFIED) {
            phase = Phase.PERSISTING;
            return;
        }
        if (repairAttempted) {
            throw new IOException("Restore batch still mismatched after repair: "
                    + current.mismatch());
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

    @Override
    public boolean persistUntil(long deadlineNanos) {
        return phase == Phase.COMPLETE;
    }

    @Override public boolean repairUntil(long deadlineNanos) { return true; }
    @Override public void restartVerification() { }

    @Override
    public WorldStateApply.ApplyProgress progress() {
        String name = switch (phase) {
            case PREPARING -> "preparing batch";
            case APPLYING -> current == null ? "loaded apply" : current.progress().phase();
            case VERIFYING -> "verification";
            case PERSISTING -> current.progress().phase();
            case REPAIRING -> "repairing";
            case LIGHTING -> "waiting for lighting";
            case COMPLETE -> "verification";
        };
        long completed = batchStart;
        if (current != null && batchStart < plan.sectionKeys().size()) {
            completed += Math.min(
                    current.progress().completed(), batchEnd - batchStart);
        }
        return new WorldStateApply.ApplyProgress(
                name, Math.min(completed, plan.sectionKeys().size()),
                plan.sectionKeys().size());
    }

    @Override
    public RestoreApplyStatistics statistics() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (preparing != null) {
            preparing.cancel(false);
            preparing = null;
        }
        slab = null;
        PreparedWorldMutationSession open = current;
        current = null;
        if (open != null) {
            open.close();
        }
    }

    static boolean sameChunk(SectionKey left, SectionKey right) {
        return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    private record Batch(
            WorldStateApply.State target,
            WorldStateApply.State base,
            List<SectionKey> order) { }

    private enum BatchKind { SECTIONS, ENTITIES, SPAWNS }
    private enum Phase {
        PREPARING, APPLYING, VERIFYING, PERSISTING, REPAIRING, LIGHTING, COMPLETE
    }
}
