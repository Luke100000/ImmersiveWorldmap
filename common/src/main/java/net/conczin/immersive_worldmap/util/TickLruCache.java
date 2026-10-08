package net.conczin.immersive_worldmap.util;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class TickLruCache<K, V> {
    private static final AtomicLong CURRENT_TICK = new AtomicLong();

    private static final class Entry<V> {
        final V value;
        long lastAccessTick;

        Entry(V value) {
            this.value = value;
            this.lastAccessTick = CURRENT_TICK.get();
        }
    }

    private final int capacity;
    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public TickLruCache(int capacity) {
        this.capacity = capacity;
    }

    public static void tick() {
        CURRENT_TICK.incrementAndGet();
    }

    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        entry.lastAccessTick = CURRENT_TICK.get();
        return entry.value;
    }

    public synchronized void put(K key, V value) {
        entries.put(key, new Entry<>(value));
        evictOldEntries();
    }

    public synchronized void remove(K key) {
        entries.remove(key);
    }

    public synchronized void removeIf(Predicate<K> predicate) {
        entries.keySet().removeIf(predicate);
    }

    public synchronized List<V> values() {
        List<V> values = new ArrayList<>(entries.size());
        for (Entry<V> entry : entries.values()) {
            values.add(entry.value);
        }
        return values;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void evictOldEntries() {
        Iterator<Entry<V>> iterator = entries.values().iterator();
        while (entries.size() > capacity && iterator.hasNext()) {
            if (iterator.next().lastAccessTick >= CURRENT_TICK.get()) break;
            iterator.remove();
        }
    }
}
