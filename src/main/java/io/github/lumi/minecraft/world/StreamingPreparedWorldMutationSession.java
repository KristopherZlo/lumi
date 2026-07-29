package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/** Decodes an estimated 128 MiB, then applies and persists it in 32-chunk windows. */
final class StreamingPreparedWorldMutationSession implements WorldStateApply.ApplySession {
    static final int MAX_CHUNKS = 32;
    static final long MAX_ESTIMATED_BYTES = 128L * 1024 * 1024;
    static final int MAX_SECTIONS_PER_SLAB = 1_024;
    private static final long RAW_SECTION_BYTES = 32L * 1024;
    private static final long NATIVE_SECTION_BYTES = 96L * 1024;
    private static final long PREPARED_DELTA_BYTES = 48L * 1024;
    private static final long TRANSIENT_PREPARATION_BYTES = 64L * 1024;
    private static final long NBT_ENTRY_BYTES = 64;
    private static final long NATIVE_NBT_ENTRY_BYTES = 256;
    // ponytail: Conservative HotSpot 21 expansion; replace only if the heap gate drifts.
    private static final long NATIVE_NBT_EXPANSION = 32;

    private final PreparedMinecraftPlanState plan;
    private final MinecraftRestorePreparation preparation;
    private final PreparedWorldAccess world;
    private final Executor background;
    private final Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads;
    private final RestoreApplyMetrics metrics = new RestoreApplyMetrics();
    private final Set<ChunkCoordinate> slabDurable = new HashSet<>();
    private int batchStart;
    private int batchEnd;
    private int slabEnd;
    private int entityBatchStart;
    private int entityBatchEnd;
    private int entityCleanupCount;
    private int entityStorageCleanupStart;
    private int entityStorageCleanupEnd;
    private CompletableFuture<PreparedMinecraftState> preparing;
    private CompletableFuture<Set<EntityChunkKey>> entityCleanup;
    private DimensionFreeze.Lease entityLoadSuppression;
    private PreparedMinecraftState entityRemoval;
    private List<UUID> cleanupEntityIds;
    private int cleanupEntityIndex;
    private List<EntityChunkKey> entityRemovalKeys;
    private List<EntityChunkKey> entityKeys;
    private EntityLookahead entityLookahead;
    private IOException entityLookaheadFailure;
    private PreparedMinecraftState slab;
    private PreparedWorldMutationSession current;
    private BatchKind currentKind;
    private Phase phase = Phase.PREPARING;
    private boolean repairAttempted;
    private boolean spawnsStarted;
    private boolean entityCleanupComplete;
    private volatile boolean closed;

    StreamingPreparedWorldMutationSession(
            PreparedMinecraftPlanState plan,
            MinecraftRestorePreparation preparation,
            PreparedWorldAccess world,
            Executor background,
            Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.world = Objects.requireNonNull(world, "world");
        this.background = Objects.requireNonNull(background, "background");
        this.chunkLoads = Objects.requireNonNull(chunkLoads, "chunkLoads");
        entityKeys = plan.entityKeys();
    }

    @Override
    public boolean applyUntil(long deadlineNanos) throws IOException {
        if (closed) {
            return false;
        }
        while (phase != Phase.COMPLETE && System.nanoTime() < deadlineNanos) {
            switch (phase) {
                case PREPARING -> {
                    if (!prepareNext(deadlineNanos)) {
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
                    boolean complete;
                    try {
                        complete = current.persistUntil(deadlineNanos);
                    } catch (IOException | RuntimeException failed) {
                        discardEntityLookahead(failed);
                        throw failed;
                    }
                    if (complete) {
                        phase = Phase.PREPARING;
                    } else {
                        if (System.nanoTime() < deadlineNanos) {
                            prefetchNextEntityBatch(deadlineNanos);
                        }
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

    private boolean prepareNext(long deadlineNanos) throws IOException {
        if (current != null) {
            Set<ChunkCoordinate> durable = currentKind == BatchKind.SECTIONS
                    ? current.durableStoredChunks() : Set.of();
            current.close();
            current = null;
            if (currentKind == BatchKind.SECTIONS) {
                slabDurable.addAll(durable);
                batchStart = batchEnd;
                if (batchStart == slabEnd) {
                    slab = null;
                    slabDurable.clear();
                }
            } else if (currentKind == BatchKind.ENTITIES) {
                entityBatchStart = entityBatchEnd;
            }
            currentKind = null;
            repairAttempted = false;
        }
        if (!prepareEntityCleanup(deadlineNanos)) {
            return false;
        }
        // Remove old/legacy UUIDs durably before terrain can load final placements.
        if (entityBatchStart < entityCleanupCount) {
            return startEntityBatch();
        }
        releaseEntityLoadSuppression();
        entityRemoval = null;
        cleanupEntityIds = null;
        if (batchStart < plan.sectionKeys().size()) {
            if (slab == null) {
                if (preparing == null) {
                    int start = batchStart;
                    preparing = CompletableFuture.supplyAsync(() -> {
                        try {
                            Batch batch = loadBatch(start);
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
                slabEnd = batchStart + slab.sectionKeys().size();
            }
            batchEnd = windowEnd(plan.sectionKeys(), batchStart, slabEnd);
            boolean lastWindow = batchEnd == slabEnd;
            current = new PreparedWorldMutationSession(
                    nextSectionWindow(), world, System::nanoTime,
                    chunkLoads.apply(ChunkLoadAccess.Readiness.TERRAIN), metrics,
                    lastWindow
                            ? PreparedWorldMutationSession.PersistenceMode.SLAB_END
                            : PreparedWorldMutationSession.PersistenceMode.STAGE,
                    slab, Set.copyOf(slabDurable));
            currentKind = BatchKind.SECTIONS;
            phase = Phase.APPLYING;
            return true;
        }
        if (entityBatchStart < entityKeys.size()) {
            return startEntityBatch();
        }
        if (!spawnsStarted && plan.source().playerSpawnsIncluded()) {
            spawnsStarted = true;
            var source = new WorldStateApply.State(
                    Map.of(), Map.of(), plan.source().playerSpawns());
            current = new PreparedWorldMutationSession(
                    new PreparedMinecraftState(source, Map.of(), Map.of()), world,
                    System::nanoTime,
                    chunkLoads.apply(ChunkLoadAccess.Readiness.TERRAIN), metrics);
            currentKind = BatchKind.SPAWNS;
            phase = Phase.APPLYING;
            return true;
        }
        phase = Phase.LIGHTING;
        return true;
    }

    private boolean startEntityBatch() throws IOException {
        current = new PreparedWorldMutationSession(
                nextEntityBatch(), world, System::nanoTime,
                nextEntityChunkLoads(), metrics);
        currentKind = BatchKind.ENTITIES;
        phase = Phase.APPLYING;
        return true;
    }

    private boolean prepareEntityCleanup(long deadlineNanos) throws IOException {
        if (entityCleanupComplete) {
            return true;
        }
        if (plan.entityKeys().isEmpty()) {
            entityCleanupComplete = true;
            return true;
        }
        if (entityLoadSuppression == null) {
            entityLoadSuppression = world.suppressEntityLoads(
                    Set.copyOf(plan.entityKeys()));
        }
        if (entityStorageCleanupStart < plan.entityKeys().size()) {
            if (entityCleanup == null) {
                if (entityRemovalKeys == null) {
                    cleanupEntityIds = List.copyOf(plan.cleanupEntityIds());
                    entityRemovalKeys = new ArrayList<>();
                }
                entityStorageCleanupEnd = entityBatchEnd(
                        plan.entityKeys().size(), entityStorageCleanupStart);
                entityRemoval = prepareEntityRemoval(plan.entityKeys().subList(
                        entityStorageCleanupStart, entityStorageCleanupEnd));
                entityCleanup = world.cleanStoredEntities(entityRemoval);
            }
            if (!entityCleanup.isDone()) {
                return false;
            }
            Set<EntityChunkKey> cleaned;
            try {
                cleaned = entityCleanup.join();
            } catch (CancellationException failed) {
                throw new IOException("Stored entity cleanup was cancelled", failed);
            } catch (CompletionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Cannot clean stored entity chunks", cause);
            }
            entityRemoval.entityKeys().stream().filter(key -> !cleaned.contains(key))
                    .forEach(entityRemovalKeys::add);
            entityStorageCleanupStart = entityStorageCleanupEnd;
            entityCleanup = null;
            entityRemoval = null;
            if (entityStorageCleanupStart < plan.entityKeys().size()) {
                return false;
            }
        }
        while (cleanupEntityIndex < cleanupEntityIds.size()
                && System.nanoTime() < deadlineNanos) {
            world.removeEntity(cleanupEntityIds.get(cleanupEntityIndex++));
        }
        if (cleanupEntityIndex < cleanupEntityIds.size()) {
            return false;
        }
        List<EntityChunkKey> ordered = new ArrayList<>(
                entityRemovalKeys.size() + plan.entityKeys().size());
        ordered.addAll(entityRemovalKeys);
        entityCleanupCount = entityRemovalKeys.size();
        ordered.addAll(plan.entityKeys());
        entityKeys = List.copyOf(ordered);
        entityRemovalKeys = null;
        entityCleanupComplete = true;
        return true;
    }

    private void releaseEntityLoadSuppression() {
        if (entityLoadSuppression == null) {
            return;
        }
        entityLoadSuppression.release();
        entityLoadSuppression = null;
    }

    private PreparedMinecraftState prepareEntityRemoval(
            List<EntityChunkKey> keys) {
        Map<EntityChunkKey, EntityChunkBlob> entities = new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        DecodedEntityChunk decodedEmpty = new DecodedEntityChunk(List.of());
        for (EntityChunkKey key : keys) {
            entities.put(key, empty);
            decoded.put(key, decodedEmpty);
        }
        return new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), entities),
                Map.of(), decoded, List.of(), keys);
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

    private Batch loadBatch(int start) {
        List<SectionKey> keys = plan.sectionKeys();
        Map<SectionKey, SectionBlob> target = new LinkedHashMap<>();
        Map<SectionKey, SectionBlob> base = new LinkedHashMap<>();
        List<SectionKey> order = new ArrayList<>();
        Map<SectionBlob, Boolean> rawSections = new IdentityHashMap<>();
        Map<SectionBlob, Boolean> nativeSections = new IdentityHashMap<>();
        long estimatedBytes = TRANSIENT_PREPARATION_BYTES;
        int limit = (int) Math.min(
                keys.size(), (long) start + MAX_SECTIONS_PER_SLAB);
        for (int index = start; index < limit; index++) {
            if (closed) {
                throw new CancellationException("Restore preparation was cancelled");
            }
            if (!target.isEmpty() && estimatedBytes >= MAX_ESTIMATED_BYTES) {
                break;
            }
            SectionKey key = keys.get(index);
            SectionBlob targetSection = plan.source().sections().get(key);
            SectionBlob baseSection = plan.base().sections().get(key);
            boolean newTargetRaw = !rawSections.containsKey(targetSection);
            boolean newBaseRaw = baseSection != targetSection
                    && !rawSections.containsKey(baseSection);
            boolean newTargetNative = !nativeSections.containsKey(targetSection);
            long additionalBytes = PREPARED_DELTA_BYTES;
            if (newTargetRaw) {
                additionalBytes = addEstimated(
                        additionalBytes, estimatedRawBytes(targetSection));
            }
            if (newBaseRaw) {
                additionalBytes = addEstimated(
                        additionalBytes, estimatedRawBytes(baseSection));
            }
            if (newTargetNative) {
                additionalBytes = addEstimated(
                        additionalBytes, estimatedNativeBytes(targetSection));
            }
            if (!target.isEmpty()
                    && additionalBytes > MAX_ESTIMATED_BYTES - estimatedBytes) {
                break;
            }
            target.put(key, targetSection);
            base.put(key, baseSection);
            order.add(key);
            rawSections.put(targetSection, Boolean.TRUE);
            rawSections.put(baseSection, Boolean.TRUE);
            nativeSections.put(targetSection, Boolean.TRUE);
            estimatedBytes = addEstimated(estimatedBytes, additionalBytes);
        }
        return new Batch(
                new WorldStateApply.State(target, Map.of()),
                new WorldStateApply.State(base, Map.of()),
                order);
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
        entityBatchEnd = nextEntityBatchEnd(entityBatchStart);
        List<EntityChunkKey> keys = entityKeys.subList(
                entityBatchStart, entityBatchEnd);
        boolean removing = entityBatchStart < entityCleanupCount;
        if (removing) {
            entityRemoval = prepareEntityRemoval(keys);
            return entityRemoval;
        }
        Map<EntityChunkKey, EntityChunkBlob> source =
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

    private void prefetchNextEntityBatch(long deadlineNanos) {
        if (currentKind != BatchKind.ENTITIES
                || entityBatchEnd == entityKeys.size()
                || entityBatchStart < entityCleanupCount
                || entityLookaheadFailure != null) {
            return;
        }
        int available = current.availableChunkTicketCapacity()
                - (entityLookahead == null
                        ? 0 : entityLookahead.chunks().totalChunks());
        if (available <= 0) {
            return;
        }
        int nextStart = entityBatchEnd;
        if (entityLookahead == null) {
            try {
                ChunkLoadSession chunks = chunkLoads.apply(
                        ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES);
                if (chunks == null) {
                    return;
                }
                entityLookahead = new EntityLookahead(nextStart, nextStart, chunks);
            } catch (RejectedExecutionException rejected) {
                return;
            } catch (RuntimeException failed) {
                entityLookaheadFailure = new IOException(
                        "Cannot start Restore entity lookahead", failed);
                return;
            }
        }
        int prefetchEnd = Math.min(
                nextEntityBatchEnd(nextStart),
                entityLookahead.end() + available);
        if (prefetchEnd == entityLookahead.end()) {
            return;
        }
        try {
            int processed = entityLookahead.chunks().prefetch(
                    entityKeys.subList(entityLookahead.end(), prefetchEnd),
                    deadlineNanos);
            entityLookahead = new EntityLookahead(
                    nextStart, entityLookahead.end() + processed,
                    entityLookahead.chunks());
        } catch (RuntimeException failed) {
            abandonEntityLookahead(failed);
        }
    }

    private ChunkLoadSession nextEntityChunkLoads() throws IOException {
        if (entityLookaheadFailure != null) {
            IOException failed = entityLookaheadFailure;
            entityLookaheadFailure = null;
            throw failed;
        }
        if (entityLookahead == null) {
            return chunkLoads.apply(ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES);
        }
        EntityLookahead ready = entityLookahead;
        entityLookahead = null;
        if (ready.start() != entityBatchStart || ready.end() > entityBatchEnd) {
            ready.chunks().close();
            throw new IOException("Restore entity lookahead no longer matches its batch");
        }
        return ready.chunks();
    }

    private void abandonEntityLookahead(RuntimeException failed) {
        EntityLookahead abandoned = entityLookahead;
        entityLookahead = null;
        RuntimeException cleanupFailure = null;
        if (abandoned != null) {
            try {
                abandoned.chunks().close();
            } catch (RuntimeException closeFailed) {
                failed.addSuppressed(closeFailed);
                cleanupFailure = closeFailed;
            }
        }
        if (!(failed instanceof RejectedExecutionException)
                || cleanupFailure != null) {
            entityLookaheadFailure = new IOException(
                    "Cannot prefetch Restore entity batch", failed);
        }
    }

    private void discardEntityLookahead(Throwable failure) {
        EntityLookahead discarded = entityLookahead;
        entityLookahead = null;
        if (discarded == null) {
            return;
        }
        try {
            discarded.chunks().close();
        } catch (RuntimeException closeFailed) {
            failure.addSuppressed(closeFailed);
        }
    }

    private static long estimatedRawBytes(SectionBlob section) {
        long bytes = RAW_SECTION_BYTES;
        for (String state : section.distinctBlockStates()) {
            bytes = addEstimated(bytes, 48L + 2L * state.length());
        }
        for (var nbt : section.blockEntities().values()) {
            bytes = addEstimated(bytes, NBT_ENTRY_BYTES + nbt.byteSize());
        }
        return bytes;
    }

    private static long estimatedNativeBytes(SectionBlob section) {
        long bytes = NATIVE_SECTION_BYTES;
        for (var nbt : section.blockEntities().values()) {
            bytes = addEstimated(bytes,
                    NATIVE_NBT_ENTRY_BYTES
                            + NATIVE_NBT_EXPANSION * nbt.byteSize());
        }
        return bytes;
    }

    private static long addEstimated(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
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

    private int nextEntityBatchEnd(int start) {
        return entityBatchEnd(
                start < entityCleanupCount ? entityCleanupCount : entityKeys.size(),
                start);
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
        entityCleanup = null;
        entityRemoval = null;
        cleanupEntityIds = null;
        entityRemovalKeys = null;
        EntityLookahead pending = entityLookahead;
        entityLookahead = null;
        entityLookaheadFailure = null;
        slab = null;
        PreparedWorldMutationSession open = current;
        current = null;
        RuntimeException closeFailure = null;
        try {
            if (open != null) {
                open.close();
            }
        } catch (RuntimeException failed) {
            closeFailure = failed;
        }
        try {
            if (pending != null) {
                pending.chunks().close();
            }
        } catch (RuntimeException failed) {
            if (closeFailure == null) {
                closeFailure = failed;
            } else {
                closeFailure.addSuppressed(failed);
            }
        }
        try {
            releaseEntityLoadSuppression();
        } catch (RuntimeException failed) {
            if (closeFailure == null) {
                closeFailure = failed;
            } else {
                closeFailure.addSuppressed(failed);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    static boolean sameChunk(SectionKey left, SectionKey right) {
        return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    private record Batch(
            WorldStateApply.State target,
            WorldStateApply.State base,
            List<SectionKey> order) { }

    private record EntityLookahead(
            int start, int end, ChunkLoadSession chunks) { }

    private enum BatchKind { SECTIONS, ENTITIES, SPAWNS }
    private enum Phase {
        PREPARING, APPLYING, VERIFYING, PERSISTING, REPAIRING, LIGHTING, COMPLETE
    }
}
