package net.conczin.immersive_worldmap.renderer;

import com.google.common.collect.MapMaker;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;

import java.util.*;
import java.util.function.Predicate;

public final class LodChunkMeshManager {
    public static final LodChunkMeshManager INSTANCE = new LodChunkMeshManager();

    private final LinkedHashMap<LodChunkMesh, Boolean> cache = new LinkedHashMap<>(256);
    private volatile Set<ChunkLodProcessor.CacheKey> viewport = Set.of();
    private long byteBudget = 128L * 1024 * 1024;

    // Cache eviction must not duplicate meshes still held by a page, selection, or pending build.
    private final Map<ChunkLodProcessor.CacheKey, LodChunkMesh> liveMeshes = new MapMaker().weakValues().makeMap();
    private final Object2IntOpenHashMap<ChunkLodProcessor.CacheKey> finerGeometry = new Object2IntOpenHashMap<>();

    public synchronized LodChunkMesh get(int cx, int cz, int lod, String dimension) {
        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(cx, cz, dimension, lod);
        LodChunkMesh mesh = liveMeshes.computeIfAbsent(key, LodChunkMesh::new);
        retain(mesh);
        return mesh;
    }

    private List<LodChunkMesh> meshes() {
        return new ArrayList<>(liveMeshes.values());
    }

    synchronized LodChunkMesh find(ChunkLodProcessor.CacheKey key) {
        return liveMeshes.get(key);
    }

    synchronized boolean hasFinerGeometry(ChunkLodProcessor.CacheKey key) {
        return finerGeometry.containsKey(key);
    }

    synchronized void geometryChanged(LodChunkMesh mesh) {
        boolean hasGeometry = mesh.geometry() != null;
        Boolean previous = cache.replace(mesh, hasGeometry);
        if (previous == null || previous == hasGeometry) return;

        ChunkLodProcessor.CacheKey key = mesh.key();
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

    synchronized void retain(LodChunkMesh mesh) {
        if (liveMeshes.get(mesh.key()) == mesh) {
            cache.putLast(mesh, cache.getOrDefault(mesh, false));
        }
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
        for (LodChunkMesh mesh : meshes) {
            keys.add(mesh.key());
        }
        if (!keys.equals(viewport)) {
            viewport = Collections.unmodifiableSet(keys);
        }
    }

    public synchronized long getEstimatedBytes() {
        return cache.keySet().stream().mapToLong(LodChunkMeshManager::estimatedBytes).sum();
    }

    private static long estimatedBytes(LodChunkMesh mesh) {
        byte[] geometry = mesh.geometry();
        return 128L + (geometry == null ? 0 : geometry.length);
    }

    public synchronized long getByteBudget() {
        return byteBudget;
    }

    public synchronized long trimToAvailableMemory(long availableBytes, long minimumBudget) {
        long bytes = getEstimatedBytes();
        long measuredBytes = bytes;
        byteBudget = Math.max(minimumBudget, (availableBytes - bytes) / 2);
        var entries = cache.entrySet().iterator();
        while (bytes > byteBudget && entries.hasNext()) {
            var entry = entries.next();
            if (viewport.contains(entry.getKey().key())) continue;

            LodChunkMesh mesh = entry.getKey();
            bytes -= estimatedBytes(mesh);
            mesh.close();
            entries.remove();
        }
        return measuredBytes;
    }

    public synchronized void prune(Predicate<ChunkLodProcessor.CacheKey> keep) {
        for (LodChunkMesh mesh : meshes()) {
            if (keep.test(mesh.key())) continue;
            mesh.close();
            cache.remove(mesh);
            liveMeshes.remove(mesh.key());
        }
        viewport = Set.of();
    }

    public synchronized void invalidate(int cx, int cz, int lod, String dimension) {
        for (int parentLod = lod; parentLod <= LodChunkData.MAX_LOD; parentLod++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    LodChunkMesh mesh = liveMeshes.get(new ChunkLodProcessor.CacheKey(cx + dx, cz + dz, dimension, parentLod));
                    if (mesh == null) {
                        continue;
                    }
                    mesh.markDirty();
                }
            }
            cx = Math.floorDiv(cx, 2);
            cz = Math.floorDiv(cz, 2);
        }
    }
}
