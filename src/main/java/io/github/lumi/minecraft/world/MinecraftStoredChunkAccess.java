package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

/** Rewrites gated, unloaded chunks through the running world's vanilla I/O worker. */
final class MinecraftStoredChunkAccess {
    private final ServerLevel level;
    private final PalettedContainerFactory containers;
    private final Map<ChunkCoordinate, ChunkLoadGate.Lease> gates =
            new ConcurrentHashMap<>();
    private volatile String phase = "loaded apply";

    MinecraftStoredChunkAccess(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        containers = PalettedContainerFactory.create(level.registryAccess());
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
        Map<ChunkCoordinate, CompletableFuture<PreparedWrite>> preparing =
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

    private CompletableFuture<PreparedWrite> prepare(
            ChunkCoordinate coordinate,
            Map<SectionKey, DecodedSection> target,
            boolean entitiesChanged) {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(target, "target");
        if (entitiesChanged || target.isEmpty() || target.values().stream()
                .anyMatch(section -> !section.hasPreparedDelta()
                        || section.preparedDelta().poiIndexes().length != 0)) {
            return CompletableFuture.completedFuture(null);
        }
        ChunkPos position = new ChunkPos(coordinate.x(), coordinate.z());
        ChunkLoadGate.Lease gate = gates.get(coordinate);
        if (gate == null) {
            gate = ChunkLoadGate.tryAcquire(level, position);
            if (gate == null) {
                return CompletableFuture.completedFuture(null);
            }
            gates.put(coordinate, gate);
        }
        phase = "stored read";
        return level.getChunkSource().chunkMap.read(position).thenApply(stored -> {
            if (stored.isEmpty()) {
                release(coordinate);
                return null;
            }
            try {
                return new PreparedWrite(coordinate, position, target,
                        patch(position, stored.orElseThrow(), target));
            } catch (UnsupportedChunk unsupported) {
                release(coordinate);
                return null;
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        });
    }

    private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>> writeAndVerify(
            Map<ChunkCoordinate, CompletableFuture<PreparedWrite>> preparing,
            long readNanos) {
        Map<ChunkCoordinate, StoredChunkApplyResult> results = new LinkedHashMap<>();
        List<PreparedWrite> writes = new ArrayList<>();
        preparing.forEach((coordinate, pending) -> {
            PreparedWrite write = pending.join();
            if (write == null) {
                results.put(coordinate, StoredChunkApplyResult.FALLBACK);
            } else {
                writes.add(write);
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
                    .thenApply(done -> verifiedResults(
                            reads, results, readNanos, writeNanos, syncNanos,
                            System.nanoTime() - verifyStarted));
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
                if (stored.isEmpty() || !matches(
                        write.position(), stored.orElseThrow(), write.target())) {
                    throw new IOException(
                            "Stored Restore verification failed for " + write.position());
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
            CompoundTag patched) { }

    String phase() {
        return phase;
    }

    private CompoundTag patch(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, DecodedSection> target) throws IOException, UnsupportedChunk {
        SerializableChunkData data = SerializableChunkData.parse(level, containers, source);
        if (!position.equals(data.chunkPos())) {
            throw new IOException("Stored chunk position mismatch: " + data.chunkPos());
        }
        Map<Integer, SerializableChunkData.SectionData> sections = sectionMap(data);
        boolean lightChanged = false;
        for (var entry : target.entrySet()) {
            int sectionY = entry.getKey().sectionY();
            var stored = sections.get(sectionY);
            if (stored == null || stored.chunkSection() == null) {
                throw new UnsupportedChunk();
            }
            DecodedSection decoded = entry.getValue();
            boolean changedLight = decoded.preparedDelta().lightChanged();
            lightChanged |= changedLight;
            sections.put(sectionY, new SerializableChunkData.SectionData(
                    sectionY,
                    decoded.replacementFor(stored.chunkSection()),
                    changedLight ? null : stored.blockLight(),
                    changedLight ? null : stored.skyLight()));
        }
        Map<Heightmap.Types, long[]> heightmaps = recalculateHeightmaps(
                data, sections, target);
        return copy(data, sections, heightmaps,
                replaceBlockEntities(data.blockEntities(), target), lightChanged).write();
    }

    private boolean matches(
            ChunkPos position,
            CompoundTag source,
            Map<SectionKey, DecodedSection> target) throws IOException {
        SerializableChunkData data = SerializableChunkData.parse(level, containers, source);
        if (!position.equals(data.chunkPos())) {
            return false;
        }
        Map<Integer, SerializableChunkData.SectionData> sections = sectionMap(data);
        for (var entry : target.entrySet()) {
            var stored = sections.get(entry.getKey().sectionY());
            if (stored == null || stored.chunkSection() == null
                    || !matchesStates(stored.chunkSection(), entry.getValue())) {
                return false;
            }
            if (!blockEntities(data.blockEntities(), entry.getKey().sectionY())
                    .equals(entry.getValue().blockEntities())) {
                return false;
            }
        }
        return true;
    }

    private Map<Heightmap.Types, long[]> recalculateHeightmaps(
            SerializableChunkData data,
            Map<Integer, SerializableChunkData.SectionData> sections,
            Map<SectionKey, DecodedSection> target) {
        Set<Integer> changedColumns = new HashSet<>();
        target.values().forEach(section -> {
            for (int index : section.preparedDelta().changedIndexes()) {
                changedColumns.add((index & 15) | (((index >>> 4) & 15) << 4));
            }
        });
        int bits = Mth.ceillog2(level.getHeight() + 1);
        Map<Heightmap.Types, long[]> recalculated = new HashMap<>();
        data.heightmaps().forEach((type, raw) -> {
            var storage = new SimpleBitStorage(bits, 256, raw.clone());
            for (int column : changedColumns) {
                int x = column & 15;
                int z = (column >>> 4) & 15;
                storage.set(column, firstAvailable(type, sections, x, z));
            }
            recalculated.put(type, storage.getRaw());
        });
        return Map.copyOf(recalculated);
    }

    private int firstAvailable(
            Heightmap.Types type,
            Map<Integer, SerializableChunkData.SectionData> sections,
            int x,
            int z) {
        for (int y = level.getMaxY() - 1; y >= level.getMinY(); y--) {
            var section = sections.get(Math.floorDiv(y, 16));
            BlockState state = section == null || section.chunkSection() == null
                    ? Blocks.AIR.defaultBlockState()
                    : section.chunkSection().getBlockState(x, Math.floorMod(y, 16), z);
            if (type.isOpaque().test(state)) {
                return y - level.getMinY() + 1;
            }
        }
        return 0;
    }

    private static SerializableChunkData copy(
            SerializableChunkData data,
            Map<Integer, SerializableChunkData.SectionData> sections,
            Map<Heightmap.Types, long[]> heightmaps,
            List<CompoundTag> blockEntities,
            boolean lightChanged) {
        return new SerializableChunkData(
                data.containerFactory(), data.chunkPos(), data.minSectionY(),
                data.lastUpdateTime(), data.inhabitedTime(), data.chunkStatus(),
                data.blendingData(), data.belowZeroRetrogen(), data.upgradeData(),
                data.carvingMask(), heightmaps, data.packedTicks(),
                data.postProcessingSections(), data.lightCorrect() && !lightChanged,
                List.copyOf(sections.values()), data.entities(), blockEntities,
                data.structureData());
    }

    private static Map<Integer, SerializableChunkData.SectionData> sectionMap(
            SerializableChunkData data) {
        Map<Integer, SerializableChunkData.SectionData> sections = new java.util.TreeMap<>();
        data.sectionData().forEach(section -> sections.put(section.y(), section));
        return sections;
    }

    private static List<CompoundTag> replaceBlockEntities(
            List<CompoundTag> stored,
            Map<SectionKey, DecodedSection> target) {
        Set<Integer> targetSections = new HashSet<>();
        target.keySet().forEach(key -> targetSections.add(key.sectionY()));
        List<CompoundTag> replaced = new ArrayList<>();
        stored.stream().filter(tag -> !targetSections.contains(
                Math.floorDiv(tag.getIntOr("y", 0), 16)))
                .map(CompoundTag::copy).forEach(replaced::add);
        for (var section : target.entrySet()) {
            SectionKey key = section.getKey();
            section.getValue().blockEntities().forEach((index, nbt) -> {
                CompoundTag full = nbt.copy();
                full.putInt("x", key.chunkX() * 16 + (index & 15));
                full.putInt("y", key.sectionY() * 16 + ((index >>> 8) & 15));
                full.putInt("z", key.chunkZ() * 16 + ((index >>> 4) & 15));
                replaced.add(full);
            });
        }
        return List.copyOf(replaced);
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
            CompoundTag canonical = source.copy();
            canonical.remove("x");
            canonical.remove("y");
            canonical.remove("z");
            result.put((Math.floorMod(y, 16) << 8)
                    | (Math.floorMod(z, 16) << 4) | Math.floorMod(x, 16), canonical);
        }
        return Map.copyOf(result);
    }

    private static boolean matchesStates(
            LevelChunkSection stored, DecodedSection target) {
        for (int index = 0; index < target.blockStates().size(); index++) {
            if (!stored.getBlockState(
                    index & 15, (index >>> 8) & 15, (index >>> 4) & 15)
                    .equals(target.blockStates().get(index))) {
                return false;
            }
        }
        return true;
    }

    private void release(ChunkCoordinate coordinate) {
        ChunkLoadGate.Lease gate = gates.remove(coordinate);
        if (gate != null) {
            gate.close();
        }
        phase = "loaded apply";
    }

    private static final class UnsupportedChunk extends Exception { }
}
