package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

    public synchronized boolean permitStore(EntityChunkKey key, EntityChunkBlob current) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(current, "current");
        EntityChunkBlob baseline = baselines.get(key);
        if (baseline == null) {
            baselines.put(key, current);
            return true;
        }
        if (!current.equals(baseline) && !current.equals(pending.get(key))) {
            pending.put(key, current);
            mutations.registerEntityMutation(key, () -> baseline);
        }
        if (!mutations.canPublish(key)) {
            return false;
        }
        baselines.put(key, current);
        pending.remove(key);
        return true;
    }

    public synchronized void discard(EntityChunkKey key) {
        baselines.remove(Objects.requireNonNull(key, "key"));
        pending.remove(key);
    }
}
