package io.github.lumi.minecraft.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Session-only ownership of delayed Minecraft work by a live root action. */
public final class CausalTokenRegistry<K, O> {
    private final Map<K, O> owners = new HashMap<>();

    public synchronized Optional<O> remember(K work, O owner) {
        return Optional.ofNullable(owners.put(Objects.requireNonNull(work, "work"),
                Objects.requireNonNull(owner, "owner")));
    }

    public synchronized Optional<O> take(K work) {
        return Optional.ofNullable(owners.remove(Objects.requireNonNull(work, "work")));
    }

    public synchronized Optional<O> owner(K work) {
        return Optional.ofNullable(owners.get(Objects.requireNonNull(work, "work")));
    }

    public synchronized Optional<O> forget(K work) {
        return Optional.ofNullable(owners.remove(Objects.requireNonNull(work, "work")));
    }

    public synchronized boolean anyMatch(java.util.function.Predicate<O> matches) {
        Objects.requireNonNull(matches, "matches");
        return owners.values().stream().anyMatch(matches);
    }

    public synchronized boolean anyKey(java.util.function.Predicate<K> matches) {
        Objects.requireNonNull(matches, "matches");
        return owners.keySet().stream().anyMatch(matches);
    }

    public synchronized Set<K> cancel(java.util.function.Predicate<O> matches) {
        Objects.requireNonNull(matches, "matches");
        Set<K> cancelled = new HashSet<>();
        owners.entrySet().removeIf(entry -> {
            if (matches.test(entry.getValue())) {
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
