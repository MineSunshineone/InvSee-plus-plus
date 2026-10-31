package com.janboerman.invsee.utils;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An atomic weak-value cache. Keys remain reachable for as long as their values do,
 * even when a caller uses a distinct but equal key. No user code runs under its lock.
 */
public final class WeakValueCache<K, V> {
    private final Map<K, Entry<K, V>> entries = new HashMap<>();
    private final ReferenceQueue<V> collected = new ReferenceQueue<>();

    public synchronized V get(K key) {
        drain();
        Entry<K, V> entry = entries.get(key);
        return entry == null ? null : entry.get();
    }

    /** Return the existing live value, or publish and return the supplied value. */
    public synchronized V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        drain();
        Entry<K, V> entry = entries.get(key);
        V existing = entry == null ? null : entry.get();
        if (existing != null) return existing;
        entries.put(key, new Entry<>(key, value, collected));
        return value;
    }

    public synchronized void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        drain();
        entries.put(key, new Entry<>(key, value, collected));
    }

    @SuppressWarnings("unchecked")
    private void drain() {
        Entry<K, V> entry;
        while ((entry = (Entry<K, V>) collected.poll()) != null) {
            // A delayed queue entry must not evict a newer replacement.
            entries.remove(entry.key, entry);
        }
    }

    private static final class Entry<K, V> extends WeakReference<V> {
        final K key;
        Entry(K key, V value, ReferenceQueue<V> queue) {
            super(value, queue);
            this.key = key;
        }
    }
}
