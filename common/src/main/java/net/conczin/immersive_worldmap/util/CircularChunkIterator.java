package net.conczin.immersive_worldmap.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class CircularChunkIterator implements Iterator<int[]> {
    private final int centerX;
    private final int centerZ;
    private final int radiusSquared;
    private final int squareSize;

    private int x;
    private int z;
    private int dx = 0;
    private int dz = -1;
    private int visited;
    private int[] next;

    public CircularChunkIterator(int centerX, int centerZ, int radius) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusSquared = radius * radius;
        this.squareSize = (radius * 2 + 1) * (radius * 2 + 1);
        advance();
    }

    private void advance() {
        next = null;
        while (visited < squareSize) {
            int currentX = x;
            int currentZ = z;
            advanceSpiral();
            if (currentX * currentX + currentZ * currentZ <= radiusSquared) {
                next = new int[]{centerX + currentX, centerZ + currentZ};
                return;
            }
        }
    }

    private void advanceSpiral() {
        if (x == z || x < 0 && x == -z || x > 0 && x == 1 - z) {
            int nextDx = -dz;
            dz = dx;
            dx = nextDx;
        }
        x += dx;
        z += dz;
        visited++;
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
