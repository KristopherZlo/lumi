package io.github.lumi.domain.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable key plan that resolves one restore payload at a time. */
public final class RestorePlanMap<K, V> extends AbstractMap<K, V> {
    @FunctionalInterface
    public interface Reader<K, V> {
        V read(K key) throws IOException;
    }

    private final Set<K> keys;
    private final Reader<K, V> reader;
    private final Set<Entry<K, V>> entries = new Entries();

    public RestorePlanMap(Set<K> keys, Reader<K, V> reader) {
        this.keys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
        this.reader = Objects.requireNonNull(reader, "reader");
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
}
