package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.TickLruCache;

import java.util.List;

public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    public static void tick() {
        TickLruCache.tick();
    }

    private final TickLruCache<ChunkLodProcessor.CacheKey, LodChunkMesh> cache = new TickLruCache<>(4096);

    public synchronized LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        LodChunkMesh existing = cache.get(key);
        if (existing != null) return existing;
        LodChunkMesh mesh = new LodChunkMesh(cx, cz, lod, dimension);
        cache.put(key, mesh);
        return mesh;
    }

    public List<LodChunkMesh> meshes() {
        return cache.values();
    }

    synchronized void retain(LodChunkMesh mesh) {
        cache.putIfAbsent(new ChunkLodProcessor.CacheKey(mesh.chunkX, mesh.chunkZ, mesh.dimension, mesh.lod), mesh);
    }

    public synchronized void clear() {
        LodChunkPageManager.INSTANCE.clear();
        for (LodChunkMesh mesh : cache.clear()) {
            mesh.close();
        }
    }

    public synchronized void invalidate(int cx, int cz, int lod, String dimension) {
        for (int parentLod = lod; parentLod <= LodChunkData.MAX_LOD; parentLod++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    LodChunkMesh mesh = cache.get(new ChunkLodProcessor.CacheKey(cx + dx, cz + dz, dimension, parentLod));
                    if (mesh != null) mesh.markDirty();
                }
            }
            cx = Math.floorDiv(cx, 2);
            cz = Math.floorDiv(cz, 2);
        }
    }
}
