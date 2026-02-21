package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.util.FrustumChunkIterator;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class LodChunkRendererManager {
    private static LodChunkRendererManager INSTANCE;

    public static LodChunkRendererManager get() {
        if (INSTANCE == null) INSTANCE = new LodChunkRendererManager();
        return INSTANCE;
    }

    public static void reset() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    private static final int TOP_LOD = 5;
    private static final float CHUNK_HEIGHT = 384f;
    private static final int CHUNK_SIZE = 16;

    private static final float SUBDIVIDE_DISTANCE_FACTOR = 2f;

    private record CameraSnapshot(Matrix4f mv, Matrix4f proj, String dimension, float camX, float camY, float camZ) {
    }


    private final AtomicReference<CameraSnapshot> pendingSnapshot = new AtomicReference<>(null);

    @SuppressWarnings("unchecked")
    private final List<RenderState>[] buffers = new List[]{new ArrayList<>(), new ArrayList<>()};
    private volatile int readIndex = 0;
    private int writeIndex = 1;

    private final Thread worker;
    private volatile boolean running = true;

    private LodChunkRendererManager() {
        worker = new Thread(this::workerLoop, "ImmersiveWorldmap-LodSelection");
        worker.setDaemon(true);
        worker.start();
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void workerLoop() {
        while (running) {
            CameraSnapshot snapshot = waitForSnapshot();
            if (snapshot == null) continue;
            buffers[writeIndex] = buildVisibleList(snapshot);
            int tmp = readIndex;
            readIndex = writeIndex;
            writeIndex = tmp;
        }
    }

    private CameraSnapshot waitForSnapshot() {
        while (running) {
            CameraSnapshot snap = pendingSnapshot.getAndSet(null);
            if (snap != null) return snap;
            LockSupport.parkNanos(1_000_000L);
            if (Thread.currentThread().isInterrupted()) return null;
        }
        return null;
    }

    private List<RenderState> buildVisibleList(CameraSnapshot snapshot) {
        List<RenderState> result = new ArrayList<>();
        FrustumChunkIterator topLevel = new FrustumChunkIterator(snapshot.mv(), snapshot.proj(), CHUNK_SIZE * (1 << TOP_LOD), CHUNK_HEIGHT);
        while (topLevel.hasNext()) {
            int[] c = topLevel.next();
            traverse(c[0], c[1], TOP_LOD, snapshot, result);
        }
        return result;
    }

    private RenderState traverse(int cx, int cz, int lod, CameraSnapshot snapshot, List<RenderState> result) {
        RenderState self = RenderStateManager.get().get(cx, cz, lod, snapshot.dimension());
        if (lod == 0 || !shouldSubdivide(cx, cz, lod, snapshot)) {
            result.add(self);
            return self;
        }

        int childLod = lod - 1, baseCx = cx * 2, baseCz = cz * 2;
        List<RenderState> childResult = new ArrayList<>(4);
        RenderState r0 = traverse(baseCx, baseCz, childLod, snapshot, childResult);
        RenderState r1 = traverse(baseCx + 1, baseCz, childLod, snapshot, childResult);
        RenderState r2 = traverse(baseCx, baseCz + 1, childLod, snapshot, childResult);
        RenderState r3 = traverse(baseCx + 1, baseCz + 1, childLod, snapshot, childResult);

        if (r0.isLoaded() && r1.isLoaded() && r2.isLoaded() && r3.isLoaded()) {
            result.addAll(childResult);
        } else {
            result.add(self);
        }
        return self;
    }

    private boolean shouldSubdivide(int cx, int cz, int lod, CameraSnapshot snap) {
        float worldSize = CHUNK_SIZE * (1 << lod);
        float centX = (cx + 0.5f) * worldSize;
        float centY = CHUNK_HEIGHT * 0.5f;
        float centZ = (cz + 0.5f) * worldSize;
        float dx = snap.camX() - centX;
        float dy = snap.camY() - centY;
        float dz = snap.camZ() - centZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist < worldSize * SUBDIVIDE_DISTANCE_FACTOR * 8.0;
    }

    public void update(Matrix4f mv, Matrix4f proj, String dimension) {
        Matrix4f mvCopy = new Matrix4f(mv);
        float camX = -(mvCopy.m00() * mvCopy.m30() + mvCopy.m10() * mvCopy.m31() + mvCopy.m20() * mvCopy.m32());
        float camY = -(mvCopy.m01() * mvCopy.m30() + mvCopy.m11() * mvCopy.m31() + mvCopy.m21() * mvCopy.m32());
        float camZ = -(mvCopy.m02() * mvCopy.m30() + mvCopy.m12() * mvCopy.m31() + mvCopy.m22() * mvCopy.m32());
        pendingSnapshot.set(new CameraSnapshot(mvCopy, new Matrix4f(proj), dimension, camX, camY, camZ));
    }

    public List<RenderState> visibleChunks() {
        return buffers[readIndex];
    }
}