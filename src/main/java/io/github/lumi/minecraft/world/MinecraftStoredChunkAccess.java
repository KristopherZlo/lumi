package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.mixin.EntityStoragePersistenceAccessor;
import io.github.lumi.mixin.PersistentEntityManagerPersistenceAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** Rewrites gated, unloaded chunks through the running world's vanilla I/O worker. */
final class MinecraftStoredChunkAccess {
    private final ServerLevel level;
    private final Executor background;
    private final PalettedContainerFactory containers;
    private final MinecraftStoredChunkPatcher patcher;
    private final MinecraftEntityChunkCapture entityCapture;
    private final Map<ChunkCoordinate, ChunkLoadGate.Lease> gates =
            new ConcurrentHashMap<>();
    private volatile String phase = "loaded apply";

    MinecraftStoredChunkAccess(
            ServerLevel level,
            Executor background,
            MinecraftEntityChunkCapture entityCapture) {
        this.level = Objects.requireNonNull(level, "level");
        this.background = Objects.requireNonNull(background, "background");
        this.entityCapture = Objects.requireNonNull(entityCapture, "entityCapture");
        containers = PalettedContainerFactory.create(level.registryAccess());
        patcher = new MinecraftStoredChunkPatcher(
                containers.blockStatesContainerCodec(),
                level.getMinY(), level.getHeight());
    }

    CompletableFuture<StoredChunkApplyResult> apply(
            ChunkCoordinate coordinate,
            Map<SectionKey, DecodedSection> target,
            boolean entitiesChanged) {
        Set<ChunkCoordinate> entityChunks = entitiesChanged
                ? Set.of(coordinate) : Set.of();
        return apply(Map.of(coordinate, target), entityChunks)
                .thenApply(results -> results.get(coordinate));
    }

    CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> apply(
            Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> targets,
            Set<ChunkCoordinate> entityChunks) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(entityChunks, "entityChunks");
        long readStarted = System.nanoTime();
        Map<ChunkCoordinate, CompletableFuture<Preparation>> preparing =
                new LinkedHashMap<>();
        targets.forEach((coordinate, target) -> preparing.put(coordinate,
                prepare(coordinate, target, entityChunks.contains(coordinate))));
        CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> result =
                CompletableFuture.allOf(
                        preparing.values().toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> write(
                        preparing, System.nanoTime() - readStarted));
        return result.whenComplete((ignored, failure) -> {
            if (failure != null) {
                targets.keySet().forEach(this::release);
            }
        });
    }

    private CompletableFuture<Preparation> prepare(
            ChunkCoordinate coordinate,
            Map<SectionKey, DecodedSection> target,
            boolean entitiesChanged) {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(target, "target");
        if (entitiesChanged) {
            return CompletableFuture.completedFuture(
                    Preparation.fallback(StoredChunkApplyResult.Outcome.ENTITY_CHANGES));
        }
        if (target.isEmpty() || target.values().stream()
                .anyMatch(section -> !section.hasPreparedDelta()
                        || section.preparedDelta().poiIndexes().length != 0)) {
            return CompletableFuture.completedFuture(
                    Preparation.fallback(StoredChunkApplyResult.Outcome.UNSUPPORTED_DELTA));
        }
        ChunkPos position = new ChunkPos(coordinate.x(), coordinate.z());
        if (!acquire(coordinate, position)) {
            return CompletableFuture.completedFuture(
                    Preparation.fallback(StoredChunkApplyResult.Outcome.RESIDENT));
        }
        phase = "stored read";
        return level.getChunkSource().chunkMap.read(position).thenApplyAsync(stored -> {
            if (stored.isEmpty()) {
                release(coordinate);
                return Preparation.fallback(StoredChunkApplyResult.Outcome.MISSING);
            }
            try {
                MinecraftStoredChunkPatcher.Patch patched = patcher.patch(
                        position, stored.orElseThrow(), target);
                return Preparation.ready(new PreparedWrite(
                        coordinate, position, target, patched.tag()));
            } catch (MinecraftStoredChunkPatcher.UnsupportedChunk unsupported) {
                release(coordinate);
                return Preparation.fallback(
                        StoredChunkApplyResult.Outcome.UNSUPPORTED_STORAGE);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    CompletableFuture<Set<EntityChunkKey>> cleanEntities(
            PreparedMinecraftState target) {
        Objects.requireNonNull(target, "target");
        if (target.entityKeys().isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        PersistentEntityManagerPersistenceAccessor<Entity> entityManager =
                entityManager();
        EntityStoragePersistenceAccessor entityAccess =
                (EntityStoragePersistenceAccessor) entityManager.lumi$permanentStorage();
        SimpleRegionStorage storage = entityAccess.lumi$simpleRegionStorage();
        Map<EntityChunkKey, ChunkCoordinate> acquired = new LinkedHashMap<>();
        Set<EntityChunkKey> fullyGated = new HashSet<>();
        Set<Long> temporaryEmptyChunks = new HashSet<>();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        Runnable releaseCleanup = () -> {
            temporaryEmptyChunks.forEach(
                    packed -> entityAccess.lumi$emptyChunks().remove(packed.longValue()));
            acquired.values().forEach(this::release);
        };
        try {
            for (EntityChunkKey key : target.entityKeys()) {
                ChunkCoordinate coordinate = ChunkCoordinate.from(key);
                ChunkPos position = new ChunkPos(key.chunkX(), key.chunkZ());
                long packed = position.toLong();
                boolean chunkGated = acquire(coordinate, position);
                if (!chunkGated && (ChunkLoadGate.isGated(level, position)
                        || entityManager.lumi$chunkLoadStatuses().containsKey(packed))) {
                    continue;
                }
                // A terrain holder may exist before its entity load is requested.
                // Mask that first load until the canonical tag is forced and reread.
                acquired.put(key, coordinate);
                if (chunkGated) {
                    fullyGated.add(key);
                }
                if (entityAccess.lumi$emptyChunks().add(packed)) {
                    temporaryEmptyChunks.add(packed);
                }
                writes.add(storage.write(position, entityTag(
                        key, target.entities().get(key))));
            }
        } catch (RuntimeException failed) {
            releaseCleanup.run();
            return CompletableFuture.failedFuture(failed);
        }
        if (writes.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        phase = "stored write";
        CompletableFuture<Set<EntityChunkKey>> result =
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                        .thenCompose(ignored -> {
                            phase = "storage sync";
                            return MinecraftRegionStorageSynchronizer.synchronize(
                                    storage,
                                    acquired.keySet().stream()
                                            .map(ChunkCoordinate::from)
                                            .toList());
                        })
                        .thenCompose(ignored -> verifyEntities(
                                storage, target, acquired.keySet(), fullyGated));
        return result.whenCompleteAsync(
                (ignored, failure) -> releaseCleanup.run(), level.getServer());
    }

    private CompletableFuture<Set<EntityChunkKey>> verifyEntities(
            SimpleRegionStorage storage,
            PreparedMinecraftState target,
            Set<EntityChunkKey> keys,
            Set<EntityChunkKey> fullyGated) {
        phase = "verification";
        Map<EntityChunkKey, CompletableFuture<java.util.Optional<CompoundTag>>> reads =
                new LinkedHashMap<>();
        keys.forEach(key -> reads.put(key, storage.read(
                new ChunkPos(key.chunkX(), key.chunkZ()))));
        return CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    for (var entry : reads.entrySet()) {
                        try {
                            EntityChunkBlob actual = entityCapture.captureStored(
                                    entry.getKey(), entry.getValue().join());
                            if (!actual.equals(target.source().entities()
                                    .get(entry.getKey()))) {
                                throw new IOException(
                                        "Stored entity cleanup verification failed for "
                                                + entry.getKey());
                            }
                        } catch (IOException failed) {
                            throw new CompletionException(failed);
                        }
                    }
                    return Set.copyOf(fullyGated);
                }, background);
    }

    static CompoundTag entityTag(
            EntityChunkKey key, DecodedEntityChunk target) {
        CompoundTag root = NbtUtils.addCurrentDataVersion(new CompoundTag());
        ListTag entities = new ListTag();
        target.entities().forEach(entity -> entities.add(entity.nbt().copy()));
        root.put("Entities", entities);
        root.store("Position", ChunkPos.CODEC,
                new ChunkPos(key.chunkX(), key.chunkZ()));
        return root;
    }

    @SuppressWarnings("unchecked")
    private PersistentEntityManagerPersistenceAccessor<Entity> entityManager() {
        return (PersistentEntityManagerPersistenceAccessor<Entity>)
                ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
    }

    private boolean acquire(
            ChunkCoordinate coordinate, ChunkPos position) {
        if (gates.containsKey(coordinate)) {
            return true;
        }
        ChunkLoadGate.Lease gate = ChunkLoadGate.tryAcquire(level, position);
        if (gate == null) {
            return false;
        }
        gates.put(coordinate, gate);
        return true;
    }

    private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> write(
            Map<ChunkCoordinate, CompletableFuture<Preparation>> preparing,
            long readNanos) {
        Map<ChunkCoordinate, StoredChunkApplyResult> results = new LinkedHashMap<>();
        List<PreparedWrite> writes = new ArrayList<>();
        preparing.forEach((coordinate, pending) -> {
            Preparation preparation = pending.join();
            if (preparation.write() == null) {
                results.put(coordinate, StoredChunkApplyResult.fallback(
                        preparation.fallback()));
            } else {
                writes.add(preparation.write());
            }
        });
        if (writes.isEmpty()) {
            phase = "loaded apply";
            return CompletableFuture.completedFuture(Map.copyOf(results));
        }
        phase = "stored write";
        long writeStarted = System.nanoTime();
        List<CompletableFuture<Void>> pendingWrites = writes.stream()
                .map(write -> level.getChunkSource().chunkMap.write(
                        write.position(), write.patched()))
                .toList();
        return CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> appliedResults(
                        writes, results, readNanos,
                        System.nanoTime() - writeStarted));
    }

    private Map<ChunkCoordinate, StoredChunkApplyResult> appliedResults(
            List<PreparedWrite> writes,
            Map<ChunkCoordinate, StoredChunkApplyResult> results,
            long readNanos,
            long writeNanos) {
        boolean first = true;
        for (PreparedWrite write : writes) {
            release(write.coordinate());
            long sectionSwaps = 0;
            long changedBlocks = 0;
            long lightSections = 0;
            for (DecodedSection section : write.target().values()) {
                PreparedSectionDelta delta = section.preparedDelta();
                int changed = delta.changedCount();
                if (changed == 0) {
                    continue;
                }
                sectionSwaps++;
                changedBlocks += changed;
                if (delta.lightChanged()) {
                    lightSections++;
                }
            }
            results.put(write.coordinate(), StoredChunkApplyResult.applied(
                    first ? readNanos : 0,
                    first ? writeNanos : 0,
                    0, 0,
                    sectionSwaps, changedBlocks, lightSections));
            first = false;
        }
        phase = "loaded apply";
        return Map.copyOf(results);
    }

    private record PreparedWrite(
            ChunkCoordinate coordinate,
            ChunkPos position,
            Map<SectionKey, DecodedSection> target,
            CompoundTag patched) { }

    private record Preparation(
            PreparedWrite write,
            StoredChunkApplyResult.Outcome fallback) {
        private static Preparation ready(PreparedWrite write) {
            return new Preparation(Objects.requireNonNull(write, "write"), null);
        }

        private static Preparation fallback(StoredChunkApplyResult.Outcome outcome) {
            return new Preparation(null, Objects.requireNonNull(outcome, "outcome"));
        }
    }

    String phase() {
        return phase;
    }

    String mismatchRaw(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, SectionBlob> target) throws IOException {
        SerializableChunkData data = SerializableChunkData.parse(level, containers, source);
        if (!position.equals(data.chunkPos())) {
            return "chunk position is " + data.chunkPos();
        }
        Map<Integer, SerializableChunkData.SectionData> sections = sectionMap(data);
        for (var entry : target.entrySet()) {
            var stored = sections.get(entry.getKey().sectionY());
            if (stored == null || stored.chunkSection() == null) {
                return "section is absent: " + entry.getKey();
            }
            int block = firstMismatchedState(stored.chunkSection(), entry.getValue());
            if (block >= 0) {
                return "block " + MinecraftPreparedWorldAccess.position(
                        entry.getKey(), block) + " expected "
                        + entry.getValue().blockStates().get(block) + " but was "
                        + stored.chunkSection().getBlockState(
                                block & 15, (block >>> 8) & 15, (block >>> 4) & 15);
            }
            Map<Integer, CanonicalNbt> actualBlockEntities = new HashMap<>();
            for (var blockEntity : blockEntities(
                    data.blockEntities(), entry.getKey().sectionY()).entrySet()) {
                actualBlockEntities.put(blockEntity.getKey(),
                        MinecraftSectionCapture.canonicalBlockEntityNbt(
                                blockEntity.getValue()));
            }
            if (!Map.copyOf(actualBlockEntities).equals(entry.getValue().blockEntities())) {
                return "block entities differ in " + entry.getKey();
            }
        }
        return null;
    }

    private static Map<Integer, SerializableChunkData.SectionData> sectionMap(
            SerializableChunkData data) {
        Map<Integer, SerializableChunkData.SectionData> sections = new java.util.TreeMap<>();
        data.sectionData().forEach(section -> sections.put(section.y(), section));
        return sections;
    }

    private static Map<Integer, CompoundTag> blockEntities(
            List<CompoundTag> stored, int sectionY) {
        Map<Integer, CompoundTag> result = new HashMap<>();
        for (CompoundTag source : stored) {
            int y = source.getIntOr("y", 0);
            if (Math.floorDiv(y, 16) != sectionY) {
                continue;
            }
            int x = source.getIntOr("x", 0);
            int z = source.getIntOr("z", 0);
            CompoundTag canonical =
                    MinecraftSectionCapture.canonicalBlockEntityTag(source);
            result.put((Math.floorMod(y, 16) << 8)
                    | (Math.floorMod(z, 16) << 4) | Math.floorMod(x, 16), canonical);
        }
        return Map.copyOf(result);
    }

    private static int firstMismatchedState(
            LevelChunkSection stored, SectionBlob target) {
        Map<BlockState, String> encoded = new java.util.IdentityHashMap<>();
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            BlockState state = stored.getBlockState(
                    index & 15, (index >>> 8) & 15, (index >>> 4) & 15);
            if (!target.blockStates().get(index).equals(encoded.computeIfAbsent(
                    state, BlockStateParser::serialize))) {
                return index;
            }
        }
        return -1;
    }

    private void release(ChunkCoordinate coordinate) {
        ChunkLoadGate.Lease gate = gates.remove(coordinate);
        if (gate != null) {
            gate.close();
        }
        phase = "loaded apply";
    }
}
