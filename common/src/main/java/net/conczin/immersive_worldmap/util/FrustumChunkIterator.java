package net.conczin.immersive_worldmap.util;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class FrustumChunkIterator implements Iterator<int[]> {

    private final float chunkSideLength;
    private final float halfSide;
    private final float halfHeight;

    // Gribb/Hartmann planes: dot(n,p)+d >= 0 means inside.
    private final float[] planeNx = new float[6];
    private final float[] planeNy = new float[6];
    private final float[] planeNz = new float[6];
    private final float[] planeD = new float[6];

    private final int minChunkX, maxChunkX;
    private final int minChunkZ, maxChunkZ;
    private int curX, curZ;
    private int[] next;

    public FrustumChunkIterator(Matrix4f mv, Matrix4f proj, float chunkSideLength, float chunkHeight) {
        this.chunkSideLength = chunkSideLength;
        this.halfSide = chunkSideLength * 0.5f;
        this.halfHeight = chunkHeight * 0.5f;
        extractFrustumPlanes(mv, proj);

        Matrix4f invClip = new Matrix4f(proj).mul(mv).invert();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            float nx = (i & 1) == 0 ? -1f : 1f;
            float ny = (i & 2) == 0 ? -1f : 1f;
            float nz = (i & 4) == 0 ? -1f : 1f;
            Vector4f corner = invClip.transform(new Vector4f(nx, ny, nz, 1f));
            float wx = corner.x / corner.w;
            float wz = corner.z / corner.w;
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minZ = Math.min(minZ, wz);
            maxZ = Math.max(maxZ, wz);
        }

        this.minChunkX = (int) Math.floor(minX / chunkSideLength) - 1;
        this.maxChunkX = (int) Math.ceil(maxX / chunkSideLength);
        this.minChunkZ = (int) Math.floor(minZ / chunkSideLength) - 1;
        this.maxChunkZ = (int) Math.ceil(maxZ / chunkSideLength);
        this.curX = minChunkX;
        this.curZ = minChunkZ;
        advance();
    }

    private void extractFrustumPlanes(Matrix4f mv, Matrix4f proj) {
        float[] r = new float[16];
        new Matrix4f(proj).mul(mv).get(r);

        float r00 = r[0], r01 = r[4], r02 = r[8], r03 = r[12];
        float r10 = r[1], r11 = r[5], r12 = r[9], r13 = r[13];
        float r20 = r[2], r21 = r[6], r22 = r[10], r23 = r[14];
        float r30 = r[3], r31 = r[7], r32 = r[11], r33 = r[15];

        setPlane(0, r30 + r00, r31 + r01, r32 + r02, r33 + r03); // left
        setPlane(1, r30 - r00, r31 - r01, r32 - r02, r33 - r03); // right
        setPlane(2, r30 + r10, r31 + r11, r32 + r12, r33 + r13); // bottom
        setPlane(3, r30 - r10, r31 - r11, r32 - r12, r33 - r13); // top
        setPlane(4, r30 + r20, r31 + r21, r32 + r22, r33 + r23); // near
        setPlane(5, r30 - r20, r31 - r21, r32 - r22, r33 - r23); // far
    }

    private void setPlane(int i, float nx, float ny, float nz, float d) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
            d /= len;
        }
        planeNx[i] = nx;
        planeNy[i] = ny;
        planeNz[i] = nz;
        planeD[i] = d;
    }

    private boolean isChunkVisible(int cx, int cz) {
        float centX = cx * chunkSideLength + halfSide;
        float centZ = cz * chunkSideLength + halfSide;
        for (int i = 0; i < 6; i++) {
            float nx = planeNx[i], ny = planeNy[i], nz = planeNz[i];
            float dist = nx * centX + ny * halfHeight + nz * centZ + planeD[i];
            float radius = Math.abs(nx) * halfSide + Math.abs(ny) * halfHeight + Math.abs(nz) * halfSide;
            if (dist + radius < 0f) return false;
        }
        return true;
    }

    private void advance() {
        next = null;
        while (curX <= maxChunkX) {
            if (isChunkVisible(curX, curZ)) {
                next = new int[]{curX, curZ};
            }
            if (++curZ > maxChunkZ) {
                curZ = minChunkZ;
                curX++;
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

