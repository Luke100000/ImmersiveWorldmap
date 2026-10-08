package net.conczin.immersive_worldmap.renderer;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
        INSTANCE.removeCollectedMeshes();
    }

    private final Map<ChunkLodProcessor.CacheKey, LodChunkMesh> cache = new LinkedHashMap<>(256, 0.75f, true);
    private volatile Set<ChunkLodProcessor.CacheKey> viewport = Set.of();
    private long byteBudget = 128L * 1024 * 1024;

    // Cache eviction must not duplicate meshes still held by a page, selection, or pending build.
    private final Map<ChunkLodProcessor.CacheKey, MeshReference> liveMeshes = new Object2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<ChunkLodProcessor.CacheKey> finerGeometry = new Object2IntOpenHashMap<>();
    private final ReferenceQueue<LodChunkMesh> collectedMeshes = new ReferenceQueue<>();

    public synchronized LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        removeCollectedMeshes();

        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        LodChunkMesh existing = cache.get(key);
        if (existing != null) {
            return existing;
        }

        MeshReference reference = liveMeshes.get(key);
        LodChunkMesh mesh = reference == null ? null : reference.get();
        if (mesh == null) {
            if (reference != null) {
                updateAvailability(reference, false);
            }
            mesh = new LodChunkMesh(key);
            liveMeshes.put(key, new MeshReference(key, mesh, collectedMeshes));
        }

        cache.put(mesh.key(), mesh);

        return mesh;
    }

    public synchronized List<LodChunkMesh> meshes() {
        List<LodChunkMesh> meshes = new ArrayList<>(liveMeshes.size());
        copyMeshesTo(meshes);
        return meshes;
    }

    synchronized void copyMeshesTo(List<LodChunkMesh> meshes) {
        removeCollectedMeshes();
        meshes.clear();
        for (MeshReference reference : liveMeshes.values()) {
            LodChunkMesh mesh = reference.get();
            if (mesh != null) meshes.add(mesh);
        }
    }

    synchronized LodChunkMesh find(ChunkLodProcessor.CacheKey key) {
        MeshReference reference = liveMeshes.get(key);
        return reference == null ? null : reference.get();
    }

    synchronized boolean hasFinerGeometry(ChunkLodProcessor.CacheKey key) {
        return finerGeometry.containsKey(key);
    }

    synchronized void geometryChanged(LodChunkMesh mesh) {
        MeshReference reference = liveMeshes.get(mesh.key());
        if (reference != null && reference.get() == mesh) {
            updateAvailability(reference, mesh.geometry() != null);
        }
    }

    synchronized void retain(LodChunkMesh mesh) {
        cache.putIfAbsent(mesh.key(), mesh);
    }

    public synchronized void clear() {
        LodChunkPageManager.INSTANCE.clear();
        for (LodChunkMesh mesh : meshes()) {
            mesh.close();
        }
        cache.clear();
        liveMeshes.clear();
        finerGeometry.clear();
        viewport = Set.of();
    }

    public void setViewport(Collection<LodChunkMesh> meshes) {
        Set<ChunkLodProcessor.CacheKey> keys = new ObjectOpenHashSet<>(meshes.size());
        for (LodChunkMesh mesh : meshes) keys.add(mesh.key());
        if (!keys.equals(viewport)) {
            viewport = Collections.unmodifiableSet(keys);
        }
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
        for (LodChunkMesh mesh : meshes()) {
            if (keep.test(mesh.key())) continue;
            mesh.close();
            cache.remove(mesh.key());
            liveMeshes.remove(mesh.key());
        }
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

    private synchronized void removeCollectedMeshes() {
        MeshReference reference;
        while ((reference = (MeshReference) collectedMeshes.poll()) != null) {
            if (liveMeshes.remove(reference.key, reference)) {
                updateAvailability(reference, false);
            }
        }
    }

    private void updateAvailability(MeshReference reference, boolean hasGeometry) {
        if (reference.hasGeometry == hasGeometry) return;

        reference.hasGeometry = hasGeometry;
        ChunkLodProcessor.CacheKey key = reference.key;
        for (int lod = key.lod() + 1; lod <= LodChunkData.MAX_LOD; lod++) {
            key = new ChunkLodProcessor.CacheKey(Math.floorDiv(key.chunkX(), 2), Math.floorDiv(key.chunkZ(), 2), key.dimension(), lod);

            int count = finerGeometry.getInt(key) + (hasGeometry ? 1 : -1);
            if (count == 0) {
                finerGeometry.removeInt(key);
            } else {
                finerGeometry.put(key, count);
            }
        }
    }

    private static final class MeshReference extends WeakReference<LodChunkMesh> {
        final ChunkLodProcessor.CacheKey key;
        boolean hasGeometry;

        MeshReference(ChunkLodProcessor.CacheKey key, LodChunkMesh mesh, ReferenceQueue<LodChunkMesh> queue) {
            super(mesh, queue);
            this.key = key;
        }
    }
}
