package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.util.PooledBytes;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;

import java.util.concurrent.atomic.AtomicLong;

public class LodChunkMesh {
    private static final AtomicLong REVISIONS = new AtomicLong();

    public final int chunkX;
    public final int chunkZ;
    public final int lod;
    public final String dimension;
    private final ChunkLodProcessor.CacheKey key;

    private volatile PooledBytes geometry;
    private boolean requested;
    private long revision = REVISIONS.incrementAndGet();
    private long currentRevision;

    LodChunkMesh(ChunkLodProcessor.CacheKey key) {
        this.chunkX = key.chunkX();
        this.chunkZ = key.chunkZ();
        this.lod = key.lod();
        this.dimension = key.dimension();
        this.key = key;
    }

    ChunkLodProcessor.CacheKey key() {
        return key;
    }

    PooledBytes geometry() {
        return geometry;
    }

    synchronized long copyGeometry(PooledBytes[] target, int slot) {
        target[slot] = geometry;
        return currentRevision;
    }

    public void requestLoad() {
        long requestedRevision;
        synchronized (this) {
            if (requested || revision == currentRevision) return;
            requested = true;
            requestedRevision = revision;
        }
        ThreadPoolUtil.failOnError(LodChunkMeshBuilder.buildMeshAsync(chunkX, chunkZ, dimension, lod).whenComplete((result, error) -> {
            boolean accepted = false;
            synchronized (this) {
                if (error == null && revision == requestedRevision) {
                    geometry = result;
                    currentRevision = requestedRevision;
                    accepted = true;
                }
                requested = false;
            }
            if (accepted) {
                LodChunkMeshManager.INSTANCE.geometryChanged(this);
            }
        }));
    }

    public synchronized void markDirty() {
        revision = REVISIONS.incrementAndGet();
    }

    public void close() {
        synchronized (this) {
            revision = REVISIONS.incrementAndGet();
            geometry = null;
        }
        LodChunkMeshManager.INSTANCE.geometryChanged(this);
    }
}
