package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

/** Rewrites gated, unloaded chunks through the running world's vanilla I/O worker. */
final class MinecraftStoredChunkAccess {
    private final ServerLevel level;
    private final Executor background;
    private final PalettedContainerFactory containers;
    private final MinecraftStoredChunkPatcher patcher;
    private final Map<ChunkCoordinate, ChunkLoadGate.Lease> gates =
            new ConcurrentHashMap<>();
    private volatile String phase = "loaded apply";

    MinecraftStoredChunkAccess(ServerLevel level, Executor background) {
        this.level = Objects.requireNonNull(level, "level");
        this.background = Objects.requireNonNull(background, "background");
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
                .thenCompose(ignored -> writeAndVerify(
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
        ChunkLoadGate.Lease gate = gates.get(coordinate);
        if (gate == null) {
            gate = ChunkLoadGate.tryAcquire(level, position);
            if (gate == null) {
                return CompletableFuture.completedFuture(
                        Preparation.fallback(StoredChunkApplyResult.Outcome.RESIDENT));
            }
            gates.put(coordinate, gate);
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
                        coordinate, position, target,
                        patched.tag(), patched.heightmaps()));
            } catch (MinecraftStoredChunkPatcher.UnsupportedChunk unsupported) {
                release(coordinate);
                return Preparation.fallback(
                        StoredChunkApplyResult.Outcome.UNSUPPORTED_STORAGE);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> writeAndVerify(
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
                .thenCompose(ignored -> synchronizeAndVerify(
                        writes, results, readNanos,
                        System.nanoTime() - writeStarted));
    }

    private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> synchronizeAndVerify(
            List<PreparedWrite> writes,
            Map<ChunkCoordinate, StoredChunkApplyResult> results,
            long readNanos,
            long writeNanos) {
        phase = "storage sync";
        long syncStarted = System.nanoTime();
        return level.getChunkSource().chunkMap.synchronize(true).thenCompose(ignored -> {
            long syncNanos = System.nanoTime() - syncStarted;
            phase = "verification";
            long verifyStarted = System.nanoTime();
            Map<PreparedWrite, CompletableFuture<java.util.Optional<CompoundTag>>> reads =
                    new LinkedHashMap<>();
            for (PreparedWrite write : writes) {
                reads.put(write, level.getChunkSource().chunkMap.read(write.position()));
            }
            return CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new))
                    .thenApplyAsync(done -> verifiedResults(
                            reads, results, readNanos, writeNanos, syncNanos,
                            System.nanoTime() - verifyStarted), background);
        });
    }

    private Map<ChunkCoordinate, StoredChunkApplyResult> verifiedResults(
            Map<PreparedWrite, CompletableFuture<java.util.Optional<CompoundTag>>> reads,
            Map<ChunkCoordinate, StoredChunkApplyResult> results,
            long readNanos,
            long writeNanos,
            long syncNanos,
            long verifyNanos) {
        boolean first = true;
        for (var entry : reads.entrySet()) {
            PreparedWrite write = entry.getKey();
            try {
                var stored = entry.getValue().join();
                String mismatch = stored.isEmpty()
                        ? "chunk is absent"
                        : mismatch(write.position(), stored.orElseThrow(),
                                write.target(), write.heightmaps());
                if (mismatch != null) {
                    throw new IOException(
                            "Stored Restore verification failed for "
                                    + write.position() + ": " + mismatch);
                }
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
            release(write.coordinate());
            results.put(write.coordinate(), StoredChunkApplyResult.applied(
                    first ? readNanos : 0,
                    first ? writeNanos : 0,
                    first ? syncNanos : 0,
                    first ? verifyNanos : 0));
            first = false;
        }
        phase = "loaded apply";
        return Map.copyOf(results);
    }

    private record PreparedWrite(
            ChunkCoordinate coordinate,
            ChunkPos position,
            Map<SectionKey, DecodedSection> target,
            CompoundTag patched,
            Map<Heightmap.Types, long[]> heightmaps) { }

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

    String mismatch(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, DecodedSection> target) throws IOException {
        return mismatch(position, source, target, null);
    }

    private String mismatch(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, DecodedSection> target,
            Map<Heightmap.Types, long[]> expectedHeightmaps) throws IOException {
        SerializableChunkData data = SerializableChunkData.parse(level, containers, source);
        if (!position.equals(data.chunkPos())) {
            return "chunk position is " + data.chunkPos();
        }
        if (expectedHeightmaps != null
                && !data.heightmaps().keySet().equals(expectedHeightmaps.keySet())) {
            return "heightmap types differ";
        }
        if (expectedHeightmaps != null) {
            for (var entry : expectedHeightmaps.entrySet()) {
                if (!Arrays.equals(entry.getValue(), data.heightmaps().get(entry.getKey()))) {
                    return "heightmap differs: " + entry.getKey();
                }
            }
        }
        Map<Integer, SerializableChunkData.SectionData> sections = sectionMap(data);
        for (var entry : target.entrySet()) {
            var stored = sections.get(entry.getKey().sectionY());
            if (stored == null || stored.chunkSection() == null) {
                return "section is absent: " + entry.getKey();
            }
            int block = firstMismatchedState(stored.chunkSection(), entry.getValue());
            if (block >= 0) {
                BlockState actual = stored.chunkSection().getBlockState(
                        block & 15, (block >>> 8) & 15, (block >>> 4) & 15);
                return "block " + MinecraftPreparedWorldAccess.position(
                        entry.getKey(), block) + " expected "
                        + entry.getValue().blockStates().get(block)
                        + " but was " + actual;
            }
            Map<Integer, CompoundTag> actualBlockEntities =
                    blockEntities(data.blockEntities(), entry.getKey().sectionY());
            if (!actualBlockEntities.equals(entry.getValue().blockEntities())) {
                return "block entities differ in " + entry.getKey()
                        + ": expectedIndexes=" + entry.getValue().blockEntities().keySet()
                        + ", actualIndexes=" + actualBlockEntities.keySet();
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
            LevelChunkSection stored, DecodedSection target) {
        for (int index = 0; index < target.blockStates().size(); index++) {
            if (!stored.getBlockState(
                    index & 15, (index >>> 8) & 15, (index >>> 4) & 15)
                    .equals(target.blockStates().get(index))) {
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
