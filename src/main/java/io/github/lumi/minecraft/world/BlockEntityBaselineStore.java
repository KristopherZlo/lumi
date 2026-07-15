package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Keeps only pre-mutation block-entity NBT for loaded sections without a durable origin. */
public final class BlockEntityBaselineStore {
    private final Map<SectionKey, Map<Integer, CanonicalNbt>> baselines = new HashMap<>();

    public synchronized void remember(SectionKey key, Map<Integer, CanonicalNbt> blockEntities) {
        Objects.requireNonNull(key, "key");
        Map<Integer, CanonicalNbt> copied = Map.copyOf(
                Objects.requireNonNull(blockEntities, "blockEntities"));
        baselines.putIfAbsent(key, copied);
    }

    public synchronized Optional<SectionBlob> takeOrigin(SectionKey key, SectionBlob current) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(current, "current");
        Map<Integer, CanonicalNbt> oldBlockEntities = baselines.remove(key);
        return oldBlockEntities == null
                ? Optional.empty()
                : Optional.of(new SectionBlob(current.blockStates(), oldBlockEntities));
    }

    public synchronized void discard(SectionKey key) {
        baselines.remove(Objects.requireNonNull(key, "key"));
    }

    public synchronized boolean contains(SectionKey key) {
        return baselines.containsKey(Objects.requireNonNull(key, "key"));
    }

    public synchronized void discardChunk(int chunkX, int chunkZ) {
        baselines.keySet().removeIf(
                key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ);
    }
}
