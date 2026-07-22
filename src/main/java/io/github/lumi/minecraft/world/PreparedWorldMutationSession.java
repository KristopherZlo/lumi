package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.UUID;

/** Applies and verifies prepared world objects one section or entity mutation at a time. */
public final class PreparedWorldMutationSession implements WorldStateApply.ApplySession {
    private final PreparedMinecraftState target;
    private final PreparedWorldAccess world;
    private final LongSupplier nanoTime;
    private final ChunkLoadSession chunks;
    private final RestoreApplyMetrics metrics;
    private final List<SectionKey> sections;
    private final List<EntityChunkKey> entities;
    private MutationCursor apply;
    private MutationCursor repair;
    private int sectionVerificationIndex;
    private int entityVerificationIndex;
    private boolean playerSpawnsVerified;
    private final Set<ChunkCoordinate> storedChunks = new HashSet<>();
    private final Map<ChunkCoordinate, Long> loadStarts = new LinkedHashMap<>();

    public PreparedWorldMutationSession(
            PreparedMinecraftState target, PreparedWorldAccess world, LongSupplier nanoTime) {
        this(target, world, nanoTime, null, new RestoreApplyMetrics());
    }

    public PreparedWorldMutationSession(
            PreparedMinecraftState target,
            PreparedWorldAccess world,
            LongSupplier nanoTime,
            ChunkLoadSession chunks) {
        this(target, world, nanoTime, chunks, new RestoreApplyMetrics());
    }

    PreparedWorldMutationSession(
            PreparedMinecraftState target,
            PreparedWorldAccess world,
            LongSupplier nanoTime,
            ChunkLoadSession chunks,
            RestoreApplyMetrics metrics) {
        this.target = Objects.requireNonNull(target, "target");
        this.world = Objects.requireNonNull(world, "world");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.chunks = chunks;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        sections = target.sectionKeys();
        entities = target.entityKeys();
        apply = new MutationCursor();
    }

    @Override
    public boolean applyUntil(long deadlineNanos) throws IOException {
        return apply.advance(deadlineNanos);
    }

    @Override
    public WorldStateApply.Verification verifyUntil(long deadlineNanos) throws IOException {
        long started = nanoTime.getAsLong();
        try {
            return verifyStepUntil(deadlineNanos);
        } finally {
            metrics.verification(Math.max(0, nanoTime.getAsLong() - started));
        }
    }

    private WorldStateApply.Verification verifyStepUntil(
            long deadlineNanos) throws IOException {
        while (sectionVerificationIndex < sections.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            SectionKey key = sections.get(sectionVerificationIndex);
            ChunkCoordinate coordinate = ChunkCoordinate.from(key);
            if (storedChunks.contains(coordinate)) {
                do {
                    sectionVerificationIndex++;
                } while (sectionVerificationIndex < sections.size()
                        && sameChunk(key, sections.get(sectionVerificationIndex)));
                continue;
            }
            if (!loadUntil(key, deadlineNanos)) {
                return WorldStateApply.Verification.IN_PROGRESS;
            }
            sectionVerificationIndex++;
            if (!target.source().sections().get(key).equals(world.captureSection(key))) {
                release(key);
                return WorldStateApply.Verification.MISMATCH;
            }
            if (sectionVerificationIndex == sections.size()
                    || !sameChunk(key, sections.get(sectionVerificationIndex))) {
                release(key);
            }
        }
        while (sectionVerificationIndex == sections.size()
                && entityVerificationIndex < entities.size()
                && nanoTime.getAsLong() < deadlineNanos) {
            EntityChunkKey key = entities.get(entityVerificationIndex);
            if (!loadUntil(key, deadlineNanos)) {
                return WorldStateApply.Verification.IN_PROGRESS;
            }
            entityVerificationIndex++;
            if (!target.source().entities().get(key).equals(world.captureEntities(key))) {
                release(key);
                return WorldStateApply.Verification.MISMATCH;
            }
            release(key);
        }
        if (sectionVerificationIndex == sections.size()
                && entityVerificationIndex == entities.size()
                && !playerSpawnsVerified
                && nanoTime.getAsLong() < deadlineNanos) {
            if (!world.matchesPlayerSpawns(target.source().playerSpawns())) {
                return WorldStateApply.Verification.MISMATCH;
            }
            playerSpawnsVerified = true;
        }
        return playerSpawnsVerified ? WorldStateApply.Verification.VERIFIED
                : WorldStateApply.Verification.IN_PROGRESS;
    }

    @Override
    public boolean repairUntil(long deadlineNanos) throws IOException {
        if (repair == null) {
            repair = new MutationCursor();
        }
        return repair.advance(deadlineNanos);
    }

    @Override
    public void restartVerification() {
        sectionVerificationIndex = 0;
        entityVerificationIndex = 0;
        playerSpawnsVerified = false;
    }

    @Override
    public void close() {
        if (chunks != null) {
            chunks.close();
        }
    }

    @Override
    public WorldStateApply.ApplyProgress progress() {
        return new WorldStateApply.ApplyProgress(
                world.mutationPhase(), apply.sectionIndex, sections.size());
    }

    @Override
    public RestoreApplyStatistics statistics() {
        return metrics.snapshot();
    }

    private boolean loadUntil(
            io.github.lumi.domain.model.HistoryKey key,
            long deadlineNanos) throws IOException {
        if (chunks == null) {
            return true;
        }
        ChunkCoordinate coordinate = ChunkCoordinate.from(key);
        long started = loadStarts.computeIfAbsent(coordinate, ignored -> nanoTime.getAsLong());
        if (!chunks.loadOneUntil(coordinate, deadlineNanos)) {
            return false;
        }
        loadStarts.remove(coordinate);
        metrics.chunkLoad(Math.max(0, nanoTime.getAsLong() - started));
        return true;
    }

    private void release(io.github.lumi.domain.model.HistoryKey key) {
        if (chunks != null) {
            chunks.release(ChunkCoordinate.from(key));
        }
    }

    private static boolean sameChunk(SectionKey left, SectionKey right) {
        return left.chunkX() == right.chunkX() && left.chunkZ() == right.chunkZ();
    }

    private final class MutationCursor {
        private int sectionIndex;
        private List<Integer> removals = List.of();
        private int removalIndex;
        private List<java.util.Map.Entry<Integer, net.minecraft.nbt.CompoundTag>> blockEntities =
                List.of();
        private int blockEntityIndex;
        private int entityIndex;
        private List<UUID> entityRemovals = List.of();
        private int entityRemovalIndex;
        private int entityAddIndex;
        private boolean entitiesApplied;
        private boolean playerSpawnsApplied;
        private SectionApplyResult appliedSection;
        private final List<SectionApplyResult> appliedChunkSections = new ArrayList<>();
        private boolean chunkBlockEntitiesChanged;
        private final Set<ChunkCoordinate> storageAttempted = new HashSet<>();
        private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> storedApply;
        private Map<ChunkCoordinate, StoredChunkApplyResult> storedResults = Map.of();
        private Phase phase = Phase.BLOCKS;

        private boolean advance(long deadlineNanos) throws IOException {
            while (phase != Phase.COMPLETE && nanoTime.getAsLong() < deadlineNanos) {
                StorageAttempt storage = tryStoredChunk();
                if (storage == StorageAttempt.WAITING) {
                    return false;
                }
                if (storage == StorageAttempt.APPLIED) {
                    continue;
                }
                var required = requiredChunk();
                if (required != null && !loadUntil(required, deadlineNanos)) {
                    return false;
                }
                long started = nanoTime.getAsLong();
                step();
                metrics.loadedApply(Math.max(0, nanoTime.getAsLong() - started));
            }
            return phase == Phase.COMPLETE;
        }

        private StorageAttempt tryStoredChunk() throws IOException {
            if (phase != Phase.BLOCKS || sectionIndex >= sections.size()) {
                return StorageAttempt.FALLBACK;
            }
            SectionKey first = sections.get(sectionIndex);
            ChunkCoordinate coordinate = ChunkCoordinate.from(first);
            if (storedChunks.contains(coordinate)) {
                skipChunk(first);
                return StorageAttempt.APPLIED;
            }
            StoredChunkApplyResult ready = storedResults.get(coordinate);
            if (ready != null) {
                storedResults = without(storedResults, coordinate);
                if (!ready.applied()) {
                    return StorageAttempt.FALLBACK;
                }
                skipChunk(first);
                return StorageAttempt.APPLIED;
            }
            if (storageAttempted.contains(coordinate) && storedApply == null) {
                return StorageAttempt.FALLBACK;
            }
            if (storedApply == null) {
                Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> batch =
                        storedBatch(sectionIndex);
                storageAttempted.addAll(batch.keySet());
                Set<ChunkCoordinate> entityChunks = new HashSet<>();
                batch.keySet().forEach(chunk -> {
                    if (target.entities().containsKey(
                            new EntityChunkKey(chunk.x(), chunk.z()))) {
                        entityChunks.add(chunk);
                    }
                });
                storedApply = world.applyStoredChunks(batch, Set.copyOf(entityChunks));
            }
            if (!storedApply.isDone()) {
                return StorageAttempt.WAITING;
            }
            try {
                storedResults = storedApply.join();
            } catch (CompletionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Stored Restore batch failed", cause);
            }
            storedApply = null;
            storedResults.forEach((chunk, result) -> {
                if (result.applied()) {
                    storedChunks.add(chunk);
                    metrics.storedChunk(result);
                }
            });
            return tryStoredChunk();
        }

        private Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> storedBatch(
                int start) {
            Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> batch =
                    new LinkedHashMap<>();
            for (int index = start; index < sections.size(); index++) {
                SectionKey key = sections.get(index);
                ChunkCoordinate chunk = ChunkCoordinate.from(key);
                if (!batch.containsKey(chunk)
                        && batch.size() == StreamingPreparedWorldMutationSession.MAX_CHUNKS) {
                    break;
                }
                batch.computeIfAbsent(chunk, ignored -> new LinkedHashMap<>())
                        .put(key, target.sections().get(key));
            }
            batch.replaceAll((ignored, value) -> Map.copyOf(value));
            return Map.copyOf(batch);
        }

        private void skipChunk(SectionKey first) {
            do {
                sectionIndex++;
            } while (sectionIndex < sections.size()
                    && sameChunk(first, sections.get(sectionIndex)));
        }

        private static <K, V> Map<K, V> without(Map<K, V> source, K key) {
            Map<K, V> copy = new LinkedHashMap<>(source);
            copy.remove(key);
            return Map.copyOf(copy);
        }

        private io.github.lumi.domain.model.HistoryKey requiredChunk() {
            if (sectionIndex < sections.size()) {
                return sections.get(sectionIndex);
            }
            if (!entitiesApplied && entityIndex < entities.size()) {
                return entities.get(entityIndex);
            }
            return null;
        }

        private void step() throws IOException {
            if (sectionIndex < sections.size()) {
                stepSection();
            } else if (!entitiesApplied) {
                stepEntities();
            } else if (!playerSpawnsApplied) {
                world.applyPlayerSpawns(target.source().playerSpawns());
                playerSpawnsApplied = true;
            } else {
                phase = Phase.COMPLETE;
            }
        }

        private void stepSection() throws IOException {
            SectionKey key = sections.get(sectionIndex);
            DecodedSection section = target.sections().get(key);
            if (phase == Phase.BLOCKS) {
                appliedSection = world.applySection(key, section);
                metrics.loadedSection(appliedSection);
                List<Integer> currentBlockEntities = world.blockEntityIndexes(key);
                removals = currentBlockEntities.stream()
                        .filter(index -> !section.blockEntities().containsKey(index))
                        .toList();
                chunkBlockEntitiesChanged |= appliedSection.blockEntitiesChanged();
                phase = Phase.REMOVE_BLOCK_ENTITIES;
            } else if (phase == Phase.REMOVE_BLOCK_ENTITIES) {
                if (removalIndex < removals.size()) {
                    world.removeBlockEntity(key, removals.get(removalIndex++));
                } else {
                    blockEntities = new ArrayList<>(section.blockEntities().entrySet());
                    phase = Phase.LOAD_BLOCK_ENTITIES;
                }
            } else if (blockEntityIndex < blockEntities.size()) {
                var blockEntity = blockEntities.get(blockEntityIndex++);
                world.loadBlockEntity(key, blockEntity.getKey(), blockEntity.getValue());
            } else {
                appliedChunkSections.add(appliedSection);
                sectionIndex++;
                if (sectionIndex == sections.size()
                        || !sameChunk(key, sections.get(sectionIndex))) {
                    ChunkSyncResult sync = world.finishChunk(
                            new ChunkCoordinate(key.chunkX(), key.chunkZ()),
                            List.copyOf(appliedChunkSections),
                            chunkBlockEntitiesChanged);
                    metrics.loadedChunk(sync);
                    appliedChunkSections.clear();
                    chunkBlockEntitiesChanged = false;
                    release(key);
                }
                removals = List.of();
                removalIndex = 0;
                blockEntities = List.of();
                blockEntityIndex = 0;
                phase = Phase.BLOCKS;
            }
        }

        private void stepEntities() throws IOException {
            if (phase == Phase.ADD_ENTITIES) {
                addEntity();
                return;
            }
            removeEntity();
        }

        private void removeEntity() throws IOException {
            if (entityIndex == entities.size()) {
                entityIndex = 0;
                phase = Phase.ADD_ENTITIES;
                return;
            }
            EntityChunkKey key = entities.get(entityIndex);
            if (phase != Phase.REMOVE_ENTITIES) {
                entityRemovals = world.durableEntityIds(key);
                phase = Phase.REMOVE_ENTITIES;
            } else if (phase == Phase.REMOVE_ENTITIES
                    && entityRemovalIndex < entityRemovals.size()) {
                world.removeEntity(key, entityRemovals.get(entityRemovalIndex++));
            } else {
                entityIndex++;
                release(key);
                entityRemovals = List.of();
                entityRemovalIndex = 0;
                phase = Phase.BLOCKS;
            }
        }

        private void addEntity() throws IOException {
            if (entityIndex == entities.size()) {
                entitiesApplied = true;
                return;
            }
            EntityChunkKey key = entities.get(entityIndex);
            DecodedEntityChunk entityChunk = target.entities().get(key);
            if (entityAddIndex < entityChunk.entities().size()) {
                world.addEntity(key, entityChunk.entities().get(entityAddIndex++));
            } else {
                entityIndex++;
                release(key);
                entityAddIndex = 0;
            }
        }
    }

    private enum Phase {
        BLOCKS,
        REMOVE_BLOCK_ENTITIES,
        LOAD_BLOCK_ENTITIES,
        REMOVE_ENTITIES,
        ADD_ENTITIES,
        COMPLETE
    }

    private enum StorageAttempt {
        WAITING,
        APPLIED,
        FALLBACK
    }
}
