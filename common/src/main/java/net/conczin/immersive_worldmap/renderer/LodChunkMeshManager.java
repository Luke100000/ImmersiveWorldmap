package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.TickLruCache;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Predicate;

public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    public static void tick() {
        TickLruCache.tick();
    }

    private final Map<ChunkLodProcessor.CacheKey, LodChunkMesh> cache = new LinkedHashMap<>(256, 0.75f, true);
    private volatile Set<ChunkLodProcessor.CacheKey> viewport = Set.of();
    private long byteBudget = 128L * 1024 * 1024;

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
        viewport = Set.of();
    }

    public void setViewport(Collection<LodChunkMesh> meshes) {
        Set<ChunkLodProcessor.CacheKey> keys = new HashSet<>();
        for (LodChunkMesh mesh : meshes) keys.add(mesh.key());
        if (!keys.equals(viewport)) viewport = Set.copyOf(keys);
    }

    Set<ChunkLodProcessor.CacheKey> viewport() {
        return viewport;
    }

    public synchronized long getEstimatedBytes() {
        return cache.values().stream().mapToLong(LodChunkMeshManager::estimatedBytes).sum();
    }

    private static long estimatedBytes(LodChunkMesh mesh) {
        byte[] geometry = mesh.geometry();
        return 128L + (geometry == null ? 0 : geometry.length);
    }

    public synchronized long getByteBudget() {
        return byteBudget;
    }

    public synchronized void trim(long budget) {
        byteBudget = budget;
        long bytes = getEstimatedBytes();
        var entries = cache.entrySet().iterator();
        while (bytes > budget && entries.hasNext()) {
            var entry = entries.next();
            if (viewport.contains(entry.getKey())) continue;
            LodChunkMesh mesh = entry.getValue();
            bytes -= estimatedBytes(mesh);
            mesh.close();
            entries.remove();
        }
    }

    public synchronized void prune(Predicate<ChunkLodProcessor.CacheKey> keep) {
        cache.entrySet().removeIf(entry -> {
            if (keep.test(entry.getKey())) return false;
            entry.getValue().close();
            liveMeshes.remove(entry.getKey());
            return true;
        });
        viewport = Set.of();
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
