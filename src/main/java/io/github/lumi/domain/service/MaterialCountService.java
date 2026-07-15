package io.github.lumi.domain.service;

import io.github.lumi.domain.model.MaterialDelta;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Counts net block materials by decoding only section objects found by Compare. */
public final class MaterialCountService {
    private static final Set<String> AIR = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private final WorldObjectRepository objects;

    public MaterialCountService(WorldObjectRepository objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public Map<String, MaterialDelta> count(WorldDifference difference) throws IOException {
        Objects.requireNonNull(difference, "difference");
        Map<ObjectId, Map<String, Long>> cache = new HashMap<>();
        Map<String, Long> before = new HashMap<>();
        Map<String, Long> after = new HashMap<>();
        for (var change : difference.sections().values()) {
            add(before, histogram(change.before(), cache));
            add(after, histogram(change.after(), cache));
        }

        Map<String, MaterialDelta> result = new TreeMap<>();
        Set<String> materials = new HashSet<>(before.keySet());
        materials.addAll(after.keySet());
        for (String material : materials) {
            long left = before.getOrDefault(material, 0L);
            long right = after.getOrDefault(material, 0L);
            if (left != right) {
                result.put(material, new MaterialDelta(left, right));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Long> histogram(
            ObjectId id, Map<ObjectId, Map<String, Long>> cache) throws IOException {
        Map<String, Long> known = cache.get(id);
        if (known != null) return known;

        Map<String, Long> counted = new HashMap<>();
        for (String state : objects.readSection(id).blockStates()) {
            String material = materialId(state);
            if (!AIR.contains(material)) {
                counted.merge(material, 1L, Math::addExact);
            }
        }
        Map<String, Long> immutable = Map.copyOf(counted);
        cache.put(id, immutable);
        return immutable;
    }

    private static String materialId(String state) throws IOException {
        int properties = state.indexOf('[');
        String id = properties < 0 ? state : state.substring(0, properties);
        if (id.isBlank()) {
            throw new IOException("Invalid persistent block state: " + state);
        }
        return id;
    }

    private static void add(Map<String, Long> total, Map<String, Long> addition) {
        addition.forEach((material, count) ->
                total.merge(material, count, Math::addExact));
    }
}
