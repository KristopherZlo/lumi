package io.github.lumi.domain.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable key plan that resolves one restore payload at a time. */
public final class RestorePlanMap<K, V> extends AbstractMap<K, V>
        implements Closeable {
    @FunctionalInterface
    public interface Reader<K, V> {
        V read(K key) throws IOException;
    }

    private final Set<K> keys;
    private final Reader<K, V> reader;
    private final Closeable resource;
    private final Set<Entry<K, V>> entries = new Entries();

    public RestorePlanMap(Set<K> keys, Reader<K, V> reader) {
        this(keys, reader, null);
    }

    RestorePlanMap(
            Collection<K> keys, Reader<K, V> reader, Closeable resource) {
        this.keys = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(keys, "keys")));
        this.reader = Objects.requireNonNull(reader, "reader");
        this.resource = resource;
    }

    static <K, V> RestorePlanMap<K, V> compose(
            Collection<K> keys, Map<K, V> primary, Map<K, V> fallback) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(fallback, "fallback");
        return new RestorePlanMap<>(keys,
                key -> read(primary.containsKey(key) ? primary : fallback, key),
                () -> close(primary, fallback));
    }

    @Override public int size() { return keys.size(); }
    @Override public boolean containsKey(Object key) { return keys.contains(key); }
    @Override public Set<K> keySet() { return keys; }
    @Override public Set<Entry<K, V>> entrySet() { return entries; }

    @Override
    public V get(Object key) {
        if (!keys.contains(key)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked") K typed = (K) key;
            return Objects.requireNonNull(reader.read(typed), "resolved value");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public Map<K, V> materialize() throws IOException {
        try {
            return Map.copyOf(this);
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    @Override
    public void close() throws IOException {
        if (resource != null) {
            resource.close();
        }
    }

    private final class Entries extends AbstractSet<Entry<K, V>> {
        @Override public int size() { return keys.size(); }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            Iterator<K> iterator = keys.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Entry<K, V> next() {
                    K key = iterator.next();
                    return Map.entry(key, get(key));
                }
            };
        }
    }

    private static <K, V> V read(Map<K, V> source, K key) throws IOException {
        try {
            return source.get(key);
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private static void close(Map<?, ?> first, Map<?, ?> second) throws IOException {
        try (Closeable primary = closeable(first);
                Closeable fallback = closeable(second)) {
            // try-with-resources preserves both close failures.
        }
    }

    static Closeable closeable(Map<?, ?> values) {
        return values instanceof Closeable closeable ? closeable : () -> { };
    }
}
