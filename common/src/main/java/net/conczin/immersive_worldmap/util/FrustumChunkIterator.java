package net.conczin.immersive_worldmap.util;

import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterates the visible chunks in a finite square centred on the camera target.
 */
public final class FrustumChunkIterator implements Iterator<int[]> {
    private final float chunkSideLength;
    private final float chunkHeight;
    private final FrustumIntersection frustum;

    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;

    private int currentX;
    private int currentZ;
    private int[] next;

    public FrustumChunkIterator(
            Matrix4fc viewProjection,
            float chunkSideLength,
            float chunkHeight,
            float centerX,
            float centerZ,
            int searchRadius
    ) {
        this.chunkSideLength = chunkSideLength;
        this.chunkHeight = chunkHeight;
        this.frustum = new FrustumIntersection(viewProjection);

        int centerChunkX = (int) Math.floor(centerX / chunkSideLength);
        int centerChunkZ = (int) Math.floor(centerZ / chunkSideLength);

        minChunkX = centerChunkX - searchRadius;
        maxChunkX = centerChunkX + searchRadius;
        minChunkZ = centerChunkZ - searchRadius;
        maxChunkZ = centerChunkZ + searchRadius;
        currentX = minChunkX;
        currentZ = minChunkZ;

        advance();
    }

    private boolean isChunkVisible(int chunkX, int chunkZ) {
        return frustum.testAab(
                chunkX * chunkSideLength, 0f, chunkZ * chunkSideLength,
                (chunkX + 1) * chunkSideLength, chunkHeight, (chunkZ + 1) * chunkSideLength
        );
    }

    private void advance() {
        next = null;
        while (currentX <= maxChunkX) {
            if (isChunkVisible(currentX, currentZ)) {
                next = new int[]{currentX, currentZ};
            }
            if (++currentZ > maxChunkZ) {
                currentZ = minChunkZ;
                currentX++;
            }
            if (next != null) return;
        }
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public int[] next() {
        if (next == null) throw new NoSuchElementException();
        int[] result = next;
        advance();
        return result;
    }
}
