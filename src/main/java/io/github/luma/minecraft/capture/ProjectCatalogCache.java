package io.github.luma.minecraft.capture;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class ProjectCatalogCache<T> {

    private final Map<String, T> values = new HashMap<>();

    synchronized T getOrLoad(String key, Loader<T> loader) throws IOException {
        T cached = this.values.get(key);
        if (cached != null) {
            return cached;
        }
        T loaded = loader.load();
        this.values.put(key, loaded);
        return loaded;
    }

    synchronized void invalidate(String key) {
        this.values.remove(key);
    }

    @FunctionalInterface
    interface Loader<T> {
        T load() throws IOException;
    }
}
