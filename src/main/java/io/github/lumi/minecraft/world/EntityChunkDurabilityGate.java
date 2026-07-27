package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Holds loaded vanilla entity baselines and delays changed stores until Lumi metadata is durable. */
public final class EntityChunkDurabilityGate {
    private final MutationDurabilityTracker mutations;
    private final Map<EntityChunkKey, EntityChunkBlob> baselines = new HashMap<>();
    private final Map<EntityChunkKey, EntityChunkBlob> pending = new HashMap<>();

    public EntityChunkDurabilityGate(MutationDurabilityTracker mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    public synchronized void rememberLoaded(EntityChunkKey key, EntityChunkBlob state) {
        baselines.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(state, "state"));
        pending.remove(key);
    }

    /** Rebases a resident chunk without turning a storage-only Restore into a live baseline. */
    public synchronized void rebaseTracked(EntityChunkKey key, EntityChunkBlob state) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        if (baselines.containsKey(key)) {
            baselines.put(key, state);
            pending.remove(key);
        }
    }

    public synchronized boolean permitStore(EntityChunkKey key, EntityChunkBlob current) {
        observeCurrent(key, current);
        if (!mutations.canPublish(key)) {
            return false;
        }
        baselines.put(key, current);
        pending.remove(key);
        return true;
    }

    public synchronized void observeCurrent(EntityChunkKey key, EntityChunkBlob current) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(current, "current");
        EntityChunkBlob baseline = baselines.get(key);
        if (baseline == null) {
            baselines.put(key, current);
            return;
        }
        EntityChunkBlob lastObserved = pending.getOrDefault(key, baseline);
        if (!current.equals(lastObserved)) {
            pending.put(key, current);
            mutations.registerEntityMutation(key, () -> baseline);
        }
    }

    /** Registers a live lifecycle change without waiting for vanilla entity storage. */
    public synchronized boolean registerMutation(EntityChunkKey key) {
        Objects.requireNonNull(key, "key");
        EntityChunkBlob baseline = baselines.get(key);
        if (baseline == null) {
            return false;
        }
        mutations.registerEntityMutation(key, () -> baseline);
        return true;
    }

    public synchronized Set<EntityChunkKey> trackedKeys() {
        return Set.copyOf(baselines.keySet());
    }

    public synchronized void discard(EntityChunkKey key) {
        baselines.remove(Objects.requireNonNull(key, "key"));
        pending.remove(key);
    }
}
