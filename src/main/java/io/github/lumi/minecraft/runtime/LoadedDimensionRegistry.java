package io.github.lumi.minecraft.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Owns the exact lifetime of one runtime for each loaded server dimension. */
public final class LoadedDimensionRegistry<K, V extends AutoCloseable> implements AutoCloseable {
    private final Map<K, V> runtimes = new HashMap<>();

    public synchronized void load(K key, V runtime) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(runtime, "runtime");
        if (runtimes.putIfAbsent(key, runtime) != null) {
            throw new IllegalStateException("Dimension runtime is already loaded: " + key);
        }
    }

    public synchronized Optional<V> find(K key) {
        return Optional.ofNullable(runtimes.get(Objects.requireNonNull(key, "key")));
    }

    public synchronized V require(K key) {
        return find(key).orElseThrow(
                () -> new IllegalStateException("Dimension runtime is not loaded: " + key));
    }

    public void unload(K key) throws Exception {
        V removed;
        synchronized (this) {
            removed = runtimes.remove(Objects.requireNonNull(key, "key"));
        }
        if (removed == null) {
            throw new IllegalStateException("Dimension runtime is not loaded: " + key);
        }
        removed.close();
    }

    public synchronized boolean isEmpty() {
        return runtimes.isEmpty();
    }

    @Override
    public void close() throws Exception {
        ArrayList<V> closing;
        synchronized (this) {
            closing = new ArrayList<>(runtimes.values());
            runtimes.clear();
        }
        Exception failure = null;
        for (V runtime : closing) {
            try {
                runtime.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
