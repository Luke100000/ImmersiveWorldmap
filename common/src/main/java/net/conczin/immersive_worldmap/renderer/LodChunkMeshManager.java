package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.TickLruCache;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    public static void tick() {
        TickLruCache.tick();
    }

    private final TickLruCache<ChunkLodProcessor.CacheKey, LodChunkMesh> cache = new TickLruCache<>(4096);

    // Cache eviction must not duplicate meshes still held by a page, selection, or pending build.
    private final Map<ChunkLodProcessor.CacheKey, MeshReference> liveMeshes = new HashMap<>();
    private final ReferenceQueue<LodChunkMesh> collectedMeshes = new ReferenceQueue<>();

    public synchronized LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        removeCollectedMeshes();
        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        LodChunkMesh existing = cache.get(key);
        if (existing != null) return existing;
        MeshReference reference = liveMeshes.get(key);
        LodChunkMesh mesh = reference == null ? null : reference.get();
        if (mesh == null) {
            mesh = new LodChunkMesh(cx, cz, lod, dimension);
            liveMeshes.put(key, new MeshReference(key, mesh, collectedMeshes));
        }
        cache.put(key, mesh);
        return mesh;
    }

    public synchronized List<LodChunkMesh> meshes() {
        removeCollectedMeshes();
        List<LodChunkMesh> meshes = new ArrayList<>(liveMeshes.size());
        for (MeshReference reference : liveMeshes.values()) {
            LodChunkMesh mesh = reference.get();
            if (mesh != null) meshes.add(mesh);
        }
        return meshes;
    }

    synchronized void retain(LodChunkMesh mesh) {
        cache.putIfAbsent(new ChunkLodProcessor.CacheKey(mesh.chunkX, mesh.chunkZ, mesh.dimension, mesh.lod), mesh);
    }

    public synchronized void clear() {
        LodChunkPageManager.INSTANCE.clear();
        for (LodChunkMesh mesh : meshes()) {
            mesh.close();
        }
        cache.clear();
        liveMeshes.clear();
    }

    public synchronized void invalidate(int cx, int cz, int lod, String dimension) {
        removeCollectedMeshes();
        for (int parentLod = lod; parentLod <= LodChunkData.MAX_LOD; parentLod++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    MeshReference reference = liveMeshes.get(
                            new ChunkLodProcessor.CacheKey(cx + dx, cz + dz, dimension, parentLod));
                    LodChunkMesh mesh = reference == null ? null : reference.get();
                    if (mesh != null) mesh.markDirty();
                }
            }
            cx = Math.floorDiv(cx, 2);
            cz = Math.floorDiv(cz, 2);
        }
    }

    private void removeCollectedMeshes() {
        MeshReference reference;
        while ((reference = (MeshReference) collectedMeshes.poll()) != null) {
            liveMeshes.remove(reference.key, reference);
        }
    }

    private static final class MeshReference extends WeakReference<LodChunkMesh> {
        final ChunkLodProcessor.CacheKey key;

        MeshReference(ChunkLodProcessor.CacheKey key, LodChunkMesh mesh, ReferenceQueue<LodChunkMesh> queue) {
            super(mesh, queue);
            this.key = key;
        }
    }
}
