package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    private static final AtomicLong currentTick = new AtomicLong(0);

    public static void tick() {
        currentTick.incrementAndGet();
    }

    private static final class Entry {
        final LodChunkMesh state;
        volatile long lastAccessTick;

        Entry(LodChunkMesh state, long tick) {
            this.state = state;
            this.lastAccessTick = tick;
        }
    }

    private static final int MAX_CAPACITY = 4096;

    // Access-order LRU; only evict entries not accessed this tick.
    private final Map<ChunkLodProcessor.CacheKey, Entry> cache = new LinkedHashMap<>(MAX_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkLodProcessor.CacheKey, Entry> eldest) {
            return size() > MAX_CAPACITY && eldest.getValue().lastAccessTick < currentTick.get();
        }
    };

    private LodChunkMeshManager() {
    }

    public LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        long tick = currentTick.get();
        Entry entry = cache.get(key);
        if (entry != null) {
            entry.lastAccessTick = tick;
            return entry.state;
        }
        LodChunkMesh mesh = new LodChunkMesh(cx, cz, lod, dimension);
        cache.put(key, new Entry(mesh, tick));
        return mesh;
    }

    public void clear() {
        for (Entry entry : cache.values()) {
            entry.state.close();
        }
        cache.clear();
    }

    public void invalidate(int cx, int cz, int lod, String dimension) {
        Entry entry = cache.get(new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod));
        if (entry != null) {
            entry.state.markDirty();
        }
    }
}
