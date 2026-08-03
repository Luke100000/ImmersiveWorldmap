package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.util.TickLruCache;

public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    public static void tick() {
        TickLruCache.tick();
    }

    private final TickLruCache<ChunkLodProcessor.CacheKey, LodChunkMesh> cache = new TickLruCache<>(4096);

    private LodChunkMeshManager() {
    }

    public LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        LodChunkMesh existing = cache.get(key);
        if (existing != null) return existing;
        LodChunkMesh mesh = new LodChunkMesh(cx, cz, lod, dimension);
        cache.put(key, mesh);
        return mesh;
    }

    public void clear() {
        for (LodChunkMesh mesh : cache.clear()) {
            mesh.close();
        }
    }

    public void invalidate(int cx, int cz, int lod, String dimension) {
        LodChunkMesh mesh = cache.get(new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod));
        if (mesh != null) mesh.markDirty();
    }

    public int getCacheSize() {
        return cache.size();
    }
}
