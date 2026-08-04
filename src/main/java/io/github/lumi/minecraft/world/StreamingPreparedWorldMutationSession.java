package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.operation.DeadlineFuture;
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
import java.util.function.Function;
import java.util.function.Predicate;

/** Double-buffers estimated 64 MiB slabs and applies bounded residency windows. */
final class StreamingPreparedWorldMutationSession implements WorldStateApply.ApplySession {
    static final int MAX_CHUNKS = 128;
    static final int MAX_ENTITY_CHUNKS = 32;
    static final long MAX_ESTIMATED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_OVERSIZED_ESTIMATED_BYTES =
            2 * MAX_ESTIMATED_BYTES;
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
    private final Predicate<ChunkCoordinate> resident;
    private final RestoreApplyMetrics metrics = new RestoreApplyMetrics();
    private final Set<ChunkCoordinate> slabStored = new HashSet<>();
    private final Set<ChunkCoordinate> poiChunks = new HashSet<>();
    private int batchStart;
    private int batchEnd;
    private int slabEnd;
    private int entityBatchStart;
    private int entityBatchEnd;
    private int entityCleanupCount;
    private int entityStorageCleanupStart;
    private int entityStorageCleanupEnd;
    private CompletableFuture<PreparedSlab> preparing;
    private CompletableFuture<Set<EntityChunkKey>> entityCleanup;
    private DimensionFreeze.Lease entityLoadSuppression;
    private PreparedMinecraftState entityRemoval;
    private List<UUID> cleanupEntityIds;
    private int cleanupEntityIndex;
    private List<EntityChunkKey> entityRemovalKeys;
    private List<EntityChunkKey> entityKeys;
    private PreparedMinecraftState slab;
    private ChunkLoadSession prewarmedChunks;
    private ChunkLoadAccess.Readiness prewarmedReadiness;
    private PreparedWorldMutationSession current;
    private BatchKind currentKind;
    private Phase phase = Phase.PREPARING;
    private boolean repairAttempted;
    private boolean spawnsStarted;
    private boolean entityCleanupComplete;
    private boolean finalPersistenceComplete;
    private long lightingStartedNanos;
    private volatile boolean closed;

    StreamingPreparedWorldMutationSession(
            PreparedMinecraftPlanState plan,
            MinecraftRestorePreparation preparation,
            PreparedWorldAccess world,
            Executor background,
            Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads) {
        this(plan, preparation, world, background, chunkLoads, ignored -> false);
    }

    @Override
    public boolean prewarmUntil(long deadlineNanos) throws IOException {
        if (closed || current != null || batchStart > 0
                || plan.sectionKeys().isEmpty()) {
            return true;
        }
        if (slab == null) {
            if (!DeadlineFuture.await(preparing, deadlineNanos)) {
                return false;
            }
            PreparedSlab prepared = claimPrepared();
            slab = prepared.state();
            slabEnd = prepared.end();
            preparing = prepared.next();
            if (preparing == null && prepared.prefetchAllowed()) {
                startSectionPreparation(false);
            }
        }
        if (prewarmedChunks == null) {
            batchEnd = windowEnd(
                    plan.sectionKeys(), batchStart, slabEnd, resident);
            PreparedMinecraftState window = nextSectionWindow();
            prewarmedReadiness = sectionReadiness(window);
            prewarmedChunks = chunkLoads.apply(prewarmedReadiness);
            prewarmedChunks.retain(window.sectionKeys());
        }
        return prewarmedChunks.loadUntil(deadlineNanos);
    }

    StreamingPreparedWorldMutationSession(
            PreparedMinecraftPlanState plan,
            MinecraftRestorePreparation preparation,
            PreparedWorldAccess world,
            Executor background,
            Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads,
            Predicate<ChunkCoordinate> resident) {
        this(plan, preparation, world, background, chunkLoads, resident,
                WorldStateApply.PrewarmHandoff.NONE);
    }

    StreamingPreparedWorldMutationSession(
            PreparedMinecraftPlanState plan,
            MinecraftRestorePreparation preparation,
            PreparedWorldAccess world,
            Executor background,
            Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads,
            Predicate<ChunkCoordinate> resident,
            WorldStateApply.PrewarmHandoff handoff) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.world = Objects.requireNonNull(world, "world");
        this.background = Objects.requireNonNull(background, "background");
        this.chunkLoads = Objects.requireNonNull(chunkLoads, "chunkLoads");
        this.resident = Objects.requireNonNull(resident, "resident");
        ChunkPrewarm transferred = handoff instanceof ChunkPrewarm prewarm
                ? prewarm : null;
        if (transferred == null) {
            Objects.requireNonNull(handoff, "handoff").close();
        }
        entityKeys = plan.entityKeys();
        startSectionPreparation(true);
        if (transferred != null) {
            prewarmedChunks = transferred.claim();
            prewarmedReadiness = transferred.readiness();
        }
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
                    if (lightingStartedNanos == 0) {
                        lightingStartedNanos = System.nanoTime();
                    }
                    if (world.finishLighting()) {
                        metrics.lighting(Math.max(
                                0, System.nanoTime() - lightingStartedNanos));
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
            Set<ChunkCoordinate> stored = currentKind == BatchKind.SECTIONS
                    ? current.storedChunkWrites() : Set.of();
            current.close();
            current = null;
            if (currentKind == BatchKind.SECTIONS) {
                slabStored.addAll(stored);
                batchStart = batchEnd;
                if (batchStart == slabEnd) {
                    slab = null;
                    slabStored.clear();
                }
            } else if (currentKind == BatchKind.ENTITIES) {
                entityBatchStart = entityBatchEnd;
            } else if (currentKind == BatchKind.FINAL) {
                finalPersistenceComplete = true;
            }
            currentKind = null;
            repairAttempted = false;
        }
        startSectionPreparation(false);
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
                if (!DeadlineFuture.await(preparing, deadlineNanos)) {
                    return false;
                }
                PreparedSlab prepared = claimPrepared();
                slab = prepared.state();
                slabEnd = prepared.end();
                preparing = prepared.next();
                if (preparing == null && prepared.prefetchAllowed()) {
                    startSectionPreparation(false);
                }
            }
            if (batchEnd <= batchStart) {
                batchEnd = windowEnd(
                        plan.sectionKeys(), batchStart, slabEnd, resident);
            }
            PreparedMinecraftState window = nextSectionWindow();
            poiChunks.addAll(window.persistencePoiChunks(Set.of()));
            ChunkLoadAccess.Readiness readiness = sectionReadiness(window);
            if (prewarmedChunks != null
                    && (prewarmedReadiness != readiness
                    || !prewarmedChunks.containsAll(window.sectionKeys()))) {
                prewarmedChunks.close();
                prewarmedChunks = null;
            }
            ChunkLoadSession windowChunks = prewarmedChunks != null
                    ? prewarmedChunks : chunkLoads.apply(readiness);
            prewarmedChunks = null;
            prewarmedReadiness = null;
            current = new PreparedWorldMutationSession(
                    window, world, System::nanoTime,
                    windowChunks, metrics,
                    PreparedWorldMutationSession.PersistenceMode.STAGE,
                    window.source(), Set.copyOf(slabStored));
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
                    chunkLoads.apply(ChunkLoadAccess.Readiness.TERRAIN), metrics,
                    PreparedWorldMutationSession.PersistenceMode.STAGE,
                    source, Set.of());
            currentKind = BatchKind.SPAWNS;
            phase = Phase.APPLYING;
            return true;
        }
        if (!finalPersistenceComplete) {
            var empty = new WorldStateApply.State(Map.of(), Map.of());
            current = new PreparedWorldMutationSession(
                    new PreparedMinecraftState(empty, Map.of(), Map.of()), world,
                    System::nanoTime, null, metrics,
                    PreparedWorldMutationSession.PersistenceMode.FINAL,
                    plan.source(), plan.sectionKeys(), plan.entityKeys(), Set.of(),
                    Set.copyOf(poiChunks));
            currentKind = BatchKind.FINAL;
            phase = Phase.APPLYING;
            return true;
        }
        phase = Phase.LIGHTING;
        return true;
    }

    private void startSectionPreparation(boolean warmNext) {
        int start = slab == null ? batchStart : slabEnd;
        if (preparing != null || start >= plan.sectionKeys().size()) {
            return;
        }
        preparing = prepareSlab(start, warmNext);
    }

    private CompletableFuture<PreparedSlab> prepareSlab(
            int start, boolean warmNext) {
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            try {
                Batch batch = loadBatch(start);
                PreparedMinecraftState state = preparation.preparePreflightedBatch(
                        batch.target(), batch.base(), batch.order(),
                        () -> closed);
                return new PreparedSlab(
                        state, start + state.sectionKeys().size(),
                        batch.estimatedBytes() <= MAX_ESTIMATED_BYTES, null);
            } catch (UncheckedIOException failed) {
                throw new CompletionException(failed.getCause());
            } catch (IOException failed) {
                throw new CompletionException(failed);
            } finally {
                metrics.batchPreparation(Math.max(0, System.nanoTime() - started));
            }
        }, background).thenApply(prepared -> {
            if (!closed && warmNext && prepared.prefetchAllowed()
                    && prepared.end() < plan.sectionKeys().size()) {
                return new PreparedSlab(
                        prepared.state(), prepared.end(), true,
                        prepareSlab(prepared.end(), false));
            }
            return prepared;
        });
    }

    private boolean startEntityBatch() {
        PreparedMinecraftState target = nextEntityBatch();
        current = new PreparedWorldMutationSession(
                target, world, System::nanoTime,
                chunkLoads.apply(ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES),
                metrics, PreparedWorldMutationSession.PersistenceMode.STAGE,
                target.source(), Set.of());
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
            if (!DeadlineFuture.await(entityCleanup, deadlineNanos)) {
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

    private PreparedSlab claimPrepared() throws IOException {
        CompletableFuture<PreparedSlab> pending = preparing;
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
        long byteLimit = MAX_ESTIMATED_BYTES;
        int limit = (int) Math.min(
                keys.size(), (long) start + MAX_SECTIONS_PER_SLAB);
        for (int index = start; index < limit; index++) {
            if (closed) {
                throw new CancellationException("Restore preparation was cancelled");
            }
            if (!target.isEmpty() && estimatedBytes >= byteLimit) {
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
                    && additionalBytes > byteLimit - estimatedBytes) {
                break;
            }
            target.put(key, targetSection);
            base.put(key, baseSection);
            order.add(key);
            rawSections.put(targetSection, Boolean.TRUE);
            rawSections.put(baseSection, Boolean.TRUE);
            nativeSections.put(targetSection, Boolean.TRUE);
            estimatedBytes = addEstimated(estimatedBytes, additionalBytes);
            if (target.size() == 1 && estimatedBytes > MAX_ESTIMATED_BYTES) {
                byteLimit = MAX_OVERSIZED_ESTIMATED_BYTES;
            }
        }
        return new Batch(
                new WorldStateApply.State(target, Map.of()),
                new WorldStateApply.State(base, Map.of()),
                order, estimatedBytes);
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

    private static ChunkLoadAccess.Readiness sectionReadiness(
            PreparedMinecraftState window) {
        boolean needsNeighbors = window.sections().values().stream()
                .anyMatch(section -> !section.hasPreparedDelta()
                        || section.preparedDelta().lightChanged());
        return needsNeighbors
                ? ChunkLoadAccess.Readiness.TERRAIN_WITH_NEIGHBORS
                : ChunkLoadAccess.Readiness.TERRAIN;
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
        return windowEnd(keys, start, limit, ignored -> false);
    }

    static int windowEnd(
            List<SectionKey> keys,
            int start,
            int limit,
            Predicate<ChunkCoordinate> resident) {
        int nonResidentChunks = 0;
        SectionKey previous = null;
        int end = start;
        int boundedLimit = Math.min(keys.size(), limit);
        while (end < boundedLimit) {
            SectionKey key = keys.get(end);
            boolean newChunk = previous == null || !sameChunk(previous, key);
            if (newChunk) {
                boolean loaded = resident.test(ChunkCoordinate.from(key));
                if (end > start && !loaded && nonResidentChunks == MAX_CHUNKS) {
                    break;
                }
                if (!loaded) {
                    nonResidentChunks++;
                }
            }
            previous = key;
            end++;
        }
        return end;
    }

    static int entityBatchEnd(int total, int start) {
        return Math.min(total, start + MAX_ENTITY_CHUNKS);
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

    @Override
    public boolean applyCompletesPersistence() {
        return true;
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
            CompletableFuture<PreparedSlab> pending = preparing;
            preparing = null;
            pending.thenAccept(prepared -> {
                if (prepared.next() != null) {
                    prepared.next().cancel(false);
                }
            });
            pending.cancel(false);
        }
        entityCleanup = null;
        entityRemoval = null;
        cleanupEntityIds = null;
        entityRemovalKeys = null;
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
            releaseEntityLoadSuppression();
        } catch (RuntimeException failed) {
            if (closeFailure == null) {
                closeFailure = failed;
            } else {
                closeFailure.addSuppressed(failed);
            }
        }
        if (prewarmedChunks != null) {
            prewarmedChunks.close();
            prewarmedChunks = null;
            prewarmedReadiness = null;
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    WorldStateApply.PrewarmHandoff suspendPrewarm() {
        ChunkPrewarm handoff = new ChunkPrewarm(
                prewarmedChunks, prewarmedReadiness);
        prewarmedChunks = null;
        prewarmedReadiness = null;
        try {
            close();
            return handoff;
        } catch (RuntimeException failed) {
            handoff.close();
            throw failed;
        }
    }

    static boolean sameChunk(SectionKey left, SectionKey right) {
        return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    private record Batch(
            WorldStateApply.State target,
            WorldStateApply.State base,
            List<SectionKey> order,
            long estimatedBytes) { }

    private record PreparedSlab(
            PreparedMinecraftState state,
            int end,
            boolean prefetchAllowed,
            CompletableFuture<PreparedSlab> next) { }

    private static final class ChunkPrewarm implements WorldStateApply.PrewarmHandoff {
        private ChunkLoadSession chunks;
        private final ChunkLoadAccess.Readiness readiness;

        private ChunkPrewarm(
                ChunkLoadSession chunks, ChunkLoadAccess.Readiness readiness) {
            this.chunks = chunks;
            this.readiness = readiness;
        }

        private ChunkLoadSession claim() {
            ChunkLoadSession claimed = chunks;
            chunks = null;
            return claimed;
        }

        private ChunkLoadAccess.Readiness readiness() {
            return readiness;
        }

        @Override
        public void close() {
            if (chunks != null) {
                chunks.close();
                chunks = null;
            }
        }
    }

    private enum BatchKind { SECTIONS, ENTITIES, SPAWNS, FINAL }
    private enum Phase {
        PREPARING, APPLYING, VERIFYING, PERSISTING, REPAIRING, LIGHTING, COMPLETE
    }
}
