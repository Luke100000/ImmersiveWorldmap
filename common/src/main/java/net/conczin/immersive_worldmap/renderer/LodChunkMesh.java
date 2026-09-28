package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

public class LodChunkMesh {
    public final int chunkX;
    public final int chunkZ;
    public final int lod;
    public final String dimension;

    private volatile byte[] geometry;
    private volatile boolean dirty = true;
    private volatile boolean requested;
    private volatile boolean loaded;

    private final AtomicLong revision = new AtomicLong();
    private final long pageGeneration = LodChunkPageManager.INSTANCE.generation();

    public LodChunkMesh(int chunkX, int chunkZ, int lod, String dimension) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.lod = lod;
        this.dimension = dimension;
    }

    public boolean isLoaded() {
        return loaded;
    }

    byte[] geometry() {
        return geometry;
    }

    long pageGeneration() {
        return pageGeneration;
    }

    public void requestLoad() {
        if (requested) return;
        if (!dirty) {
            if (!loaded && geometry != null) {
                LodChunkPageManager.INSTANCE.offer(this, geometry);
            }
            return;
        }
        requested = true;
        long requestedRevision = revision.get();
        LodChunkMeshBuilder.buildMeshAsync(chunkX, chunkZ, dimension, lod).whenComplete((result, error) -> {
            if (error == null && revision.get() == requestedRevision) {
                byte[] previous = geometry;
                geometry = result;
                dirty = false;
                if (result.length == 0) {
                    loaded = true;
                }
                if (result.length > 0 || previous != null && previous.length > 0) {
                    LodChunkPageManager.INSTANCE.offer(this, result);
                }
            } else if (error != null && !isCancellation(error)) {
                ImmersiveWorldmap.LOGGER.error("Failed to load chunk LOD data: {}", error.getMessage());
            }
            requested = false;
        });
    }

    public void markDirty() {
        revision.incrementAndGet();
        dirty = true;
    }

    void onPageUploaded(byte[] uploaded) {
        if (geometry == uploaded) loaded = true;
    }

    void onPageEvicted() {
        if (geometry != null && geometry.length > 0) loaded = false;
    }

    public void close() {
        revision.incrementAndGet();
        geometry = null;
        dirty = true;
        requested = false;
        loaded = false;
    }

    private static boolean isCancellation(Throwable error) {
        while (error != null) {
            if (error instanceof CancellationException) return true;
            error = error.getCause();
        }
        return false;
    }
}
