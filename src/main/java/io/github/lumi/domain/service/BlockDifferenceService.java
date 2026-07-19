package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.MaterialDelta;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Decodes sparse changed sections into bounded directional block batches. */
public final class BlockDifferenceService {
    public static final int DEFAULT_BATCH_SIZE = 2_048;
    public static final int MAX_BATCH_SIZE = 4_096;
    private static final Set<String> AIR = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final Comparator<SectionKey> SECTION_ORDER =
            Comparator.comparingInt(SectionKey::chunkX)
                    .thenComparingInt(SectionKey::sectionY)
                    .thenComparingInt(SectionKey::chunkZ);
    private final WorldObjectRepository objects;

    public BlockDifferenceService(WorldObjectRepository objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public Result scan(
            WorldDifference difference,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        return scan(difference, DEFAULT_BATCH_SIZE, cancelled, batches);
    }

    public Result scan(
            WorldDifference difference,
            int batchSize,
            BooleanSupplier cancelled,
            Consumer<List<BlockChange>> batches) throws IOException {
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(batches, "batches");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid block difference batch size");
        }

        var batch = new ArrayList<BlockChange>(batchSize);
        Map<String, Long> beforeMaterials = new HashMap<>();
        Map<String, Long> afterMaterials = new HashMap<>();
        long changedBlocks = 0;
        var entries = difference.sections().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(SECTION_ORDER))
                .toList();
        for (var entry : entries) {
            checkCancelled(cancelled);
            SectionBlob before = objects.readSection(entry.getValue().before());
            SectionBlob after = objects.readSection(entry.getValue().after());
            SectionKey key = entry.getKey();
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                if ((index & 255) == 0) checkCancelled(cancelled);
                String beforeState = before.blockStates().get(index);
                String afterState = after.blockStates().get(index);
                count(beforeMaterials, beforeState);
                count(afterMaterials, afterState);
                if (beforeState.equals(afterState)
                        && Objects.equals(
                                before.blockEntities().get(index),
                                after.blockEntities().get(index))) {
                    continue;
                }
                batch.add(change(key, index, beforeState, afterState));
                changedBlocks = Math.addExact(changedBlocks, 1);
                if (batch.size() == batchSize) {
                    batches.accept(List.copyOf(batch));
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            batches.accept(List.copyOf(batch));
        }
        return new Result(changedBlocks, materialChanges(
                beforeMaterials, afterMaterials));
    }

    private static BlockChange change(
            SectionKey key, int index, String before, String after) throws IOException {
        int x = index & 15;
        int z = index >>> 4 & 15;
        int y = index >>> 8 & 15;
        BlockChange.Kind kind = isAir(before) && !isAir(after)
                ? BlockChange.Kind.ADDED
                : !isAir(before) && isAir(after)
                        ? BlockChange.Kind.REMOVED : BlockChange.Kind.CHANGED;
        return new BlockChange(
                key.chunkX() * 16 + x,
                key.sectionY() * 16 + y,
                key.chunkZ() * 16 + z,
                kind);
    }

    private static void count(Map<String, Long> counts, String state)
            throws IOException {
        String material = materialId(state);
        if (!AIR.contains(material)) {
            counts.merge(material, 1L, Math::addExact);
        }
    }

    private static Map<String, MaterialDelta> materialChanges(
            Map<String, Long> before, Map<String, Long> after) {
        Map<String, MaterialDelta> changes = new TreeMap<>();
        Set<String> materials = new HashSet<>(before.keySet());
        materials.addAll(after.keySet());
        for (String material : materials) {
            long left = before.getOrDefault(material, 0L);
            long right = after.getOrDefault(material, 0L);
            if (left != right) {
                changes.put(material, new MaterialDelta(left, right));
            }
        }
        return Map.copyOf(changes);
    }

    private static String materialId(String state) throws IOException {
        int properties = state.indexOf('[');
        String id = properties < 0 ? state : state.substring(0, properties);
        if (id.isBlank()) {
            throw new IOException("Invalid persistent block state: " + state);
        }
        return id;
    }

    private static boolean isAir(String state) throws IOException {
        return AIR.contains(materialId(state));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Compare cancelled");
        }
    }

    public record Result(long changedBlocks, Map<String, MaterialDelta> materials) {
        public Result {
            materials = Map.copyOf(Objects.requireNonNull(materials, "materials"));
            if (changedBlocks < 0) {
                throw new IllegalArgumentException("Changed blocks cannot be negative");
            }
        }
    }
}
