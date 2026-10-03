package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;

import java.util.concurrent.CancellationException;

public class LodChunkMesh {
    public final int chunkX;
    public final int chunkZ;
    public final int lod;
    public final String dimension;

    private volatile byte[] geometry;
    private volatile boolean dirty = true;
    private volatile boolean requested;
    private volatile boolean loaded;

    private long revision;
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
        long requestedRevision;
        byte[] cached;
        boolean build;
        synchronized (this) {
            if (requested) return;
            cached = geometry;
            build = dirty;
            requestedRevision = revision;
            if (!build) {
                if (loaded || cached == null) return;
            } else {
                requested = true;
            }
        }
        if (!build) {
            LodChunkPageManager.INSTANCE.offer(this, cached);
            return;
        }
        LodChunkMeshBuilder.buildMeshAsync(chunkX, chunkZ, dimension, lod).whenComplete((result, error) -> {
            boolean accepted = false;
            synchronized (this) {
                if (error == null && revision == requestedRevision) {
                    geometry = result;
                    dirty = false;
                    accepted = true;
                }
                requested = false;
            }
            if (accepted) {
                LodChunkPageManager.INSTANCE.offer(this, result);
            } else if (error != null && !isCancellation(error)) {
                ImmersiveWorldmap.LOGGER.error("Failed to load chunk LOD data: {}", error.getMessage());
            }
        });
    }

    public synchronized void markDirty() {
        revision++;
        dirty = true;
    }

    synchronized void onPageUploaded(byte[] uploaded) {
        if (geometry == uploaded) loaded = true;
    }

    synchronized void onPageEvicted() {
        if (geometry != null && geometry.length > 0) loaded = false;
    }

    public synchronized void close() {
        revision++;
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
