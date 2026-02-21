package net.conczin.immersive_worldmap.lod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


public final class RenderStateManager {
    private static final RenderStateManager INSTANCE = new RenderStateManager();

    public static RenderStateManager get() {
        return INSTANCE;
    }

    private static final AtomicLong currentTick = new AtomicLong(0);

    public static void tick() {
        currentTick.incrementAndGet();
    }

    private static final class Entry {
        final RenderState state;
        volatile long lastAccessTick;

        Entry(RenderState state, long tick) {
            this.state = state;
            this.lastAccessTick = tick;
        }
    }

    private static final int MAX_CAPACITY = 4096;

    // Access-order LRU; only evicts entries not accessed this tick.
    private final Map<ChunkLodProcessor.CacheKey, Entry> cache = new LinkedHashMap<>(MAX_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkLodProcessor.CacheKey, Entry> eldest) {
            return size() > MAX_CAPACITY && eldest.getValue().lastAccessTick < currentTick.get();
        }
    };

    private RenderStateManager() {
    }

    public RenderState get(int cx, int cz, int lod, String dimension) {
        ChunkLodProcessor.CacheKey k = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        long tick = currentTick.get();
        Entry entry = cache.get(k);
        if (entry != null) {
            entry.lastAccessTick = tick;
            return entry.state;
        }
        RenderState rs = new RenderState(cx, cz, lod, dimension);
        rs.requestLoad();
        cache.put(k, new Entry(rs, tick));
        return rs;
    }

    public void clear() {
        cache.clear();
    }
}
