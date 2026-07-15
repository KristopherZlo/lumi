package io.github.lumi.minecraft.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Session-only ownership of delayed Minecraft work by a live root action. */
public final class CausalTokenRegistry<K> {
    private final Map<K, UUID> owners = new HashMap<>();

    public synchronized Optional<UUID> remember(K work, UUID action) {
        return Optional.ofNullable(owners.put(Objects.requireNonNull(work, "work"),
                Objects.requireNonNull(action, "action")));
    }

    public synchronized Optional<UUID> take(K work) {
        return Optional.ofNullable(owners.remove(Objects.requireNonNull(work, "work")));
    }

    public synchronized Optional<UUID> owner(K work) {
        return Optional.ofNullable(owners.get(Objects.requireNonNull(work, "work")));
    }

    public synchronized Optional<UUID> forget(K work) {
        return Optional.ofNullable(owners.remove(Objects.requireNonNull(work, "work")));
    }

    public synchronized Set<K> cancel(UUID action) {
        Objects.requireNonNull(action, "action");
        Set<K> cancelled = new HashSet<>();
        owners.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(action)) {
                cancelled.add(entry.getKey());
                return true;
            }
            return false;
        });
        return Set.copyOf(cancelled);
    }

    public synchronized void clear() {
        owners.clear();
    }

    public synchronized Set<K> drain() {
        Set<K> work = Set.copyOf(owners.keySet());
        owners.clear();
        return work;
    }
}
