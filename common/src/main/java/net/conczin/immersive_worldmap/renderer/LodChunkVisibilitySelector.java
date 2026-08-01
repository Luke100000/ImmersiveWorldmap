package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.util.FrustumChunkIterator;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class LodChunkVisibilitySelector {
    private static LodChunkVisibilitySelector INSTANCE;

    public static LodChunkVisibilitySelector get() {
        if (INSTANCE == null) INSTANCE = new LodChunkVisibilitySelector();
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

    private static final float SEA_LEVEL = 0f;
    private static final float SUBDIVIDE_DISTANCE_FACTOR = 8f;
    private static final float MIN_FORWARD_Y = 1.0e-4f;

    private record CameraSnapshot(
            Matrix4f viewProjection,
            String dimension,
            float focusX,
            float focusZ,
            float cameraX,
            float cameraY,
            float cameraZ,
            int rootSearchRadius
    ) {
    }

    private final AtomicReference<CameraSnapshot> pendingSnapshot = new AtomicReference<>(null);

    @SuppressWarnings("unchecked")
    private final List<LodChunkMesh>[] buffers = new List[]{new ArrayList<>(), new ArrayList<>()};
    private volatile int readIndex = 0;
    private int writeIndex = 1;

    private final Thread worker;
    private volatile boolean running = true;

    private LodChunkVisibilitySelector() {
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

    private List<LodChunkMesh> buildVisibleList(CameraSnapshot snapshot) {
        List<LodChunkMesh> result = new ArrayList<>();
        float topLevelSize = CHUNK_SIZE * (1 << TOP_LOD);
        FrustumIntersection frustum = new FrustumIntersection(snapshot.viewProjection());
        FrustumChunkIterator topLevel = new FrustumChunkIterator(
                snapshot.viewProjection(), topLevelSize, CHUNK_HEIGHT,
                snapshot.focusX(), snapshot.focusZ(), snapshot.rootSearchRadius()
        );
        while (topLevel.hasNext()) {
            int[] c = topLevel.next();
            traverse(c[0], c[1], TOP_LOD, snapshot, frustum, result);
        }
        return result;
    }

    private boolean traverse(
            int cx,
            int cz,
            int lod,
            CameraSnapshot snapshot,
            FrustumIntersection frustum,
            List<LodChunkMesh> result
    ) {
        float worldSize = CHUNK_SIZE * (1 << lod);
        if (!frustum.testAab(cx * worldSize, 0f, cz * worldSize,
                (cx + 1) * worldSize, CHUNK_HEIGHT, (cz + 1) * worldSize)) {
            return true;
        }

        LodChunkMesh self = LodChunkMeshManager.INSTANCE.get(cx, cz, lod, snapshot.dimension());
        if (lod == 0 || !shouldSubdivide(cx, cz, lod, snapshot)) {
            result.add(self);
            return self.isLoaded();
        }

        int childLod = lod - 1, baseCx = cx * 2, baseCz = cz * 2;
        List<LodChunkMesh> childResult = new ArrayList<>(4);
        boolean childrenReady = traverse(baseCx, baseCz, childLod, snapshot, frustum, childResult)
                                & traverse(baseCx + 1, baseCz, childLod, snapshot, frustum, childResult)
                                & traverse(baseCx, baseCz + 1, childLod, snapshot, frustum, childResult)
                                & traverse(baseCx + 1, baseCz + 1, childLod, snapshot, frustum, childResult);

        if (childrenReady || !self.isLoaded()) {
            result.addAll(childResult);
            return childrenReady;
        } else {
            result.add(self);
            return self.isLoaded();
        }
    }

    private boolean shouldSubdivide(int cx, int cz, int lod, CameraSnapshot snapshot) {
        float worldSize = CHUNK_SIZE * (1 << lod);
        float centerX = (cx + 0.5f) * worldSize;
        float centerY = CHUNK_HEIGHT * 0.5f;
        float centerZ = (cz + 0.5f) * worldSize;
        float dx = snapshot.cameraX() - centerX;
        float dy = snapshot.cameraY() - centerY;
        float dz = snapshot.cameraZ() - centerZ;
        return dx * dx + dy * dy + dz * dz < worldSize * worldSize * SUBDIVIDE_DISTANCE_FACTOR * SUBDIVIDE_DISTANCE_FACTOR;
    }

    public void update(Matrix4f mv, Matrix4f proj, String dimension) {
        Matrix4f inverseView = new Matrix4f(mv).invert();
        Vector3f eye = inverseView.transformPosition(new Vector3f());
        Vector3f forward = inverseView.transformDirection(new Vector3f(0f, 0f, -1f));
        float distanceToSeaLevel = Math.abs(forward.y) > MIN_FORWARD_Y ? (SEA_LEVEL - eye.y) / forward.y : 0f;
        float focusX = eye.x + forward.x * Math.max(0f, distanceToSeaLevel);
        float focusZ = eye.z + forward.z * Math.max(0f, distanceToSeaLevel);

        Matrix4f viewProjection = new Matrix4f(proj).mul(mv);
        pendingSnapshot.set(new CameraSnapshot(viewProjection, dimension, focusX, focusZ,
                eye.x, eye.y, eye.z, rootSearchRadius(viewProjection, focusX, focusZ)));
    }

    private int rootSearchRadius(Matrix4f viewProjection, float focusX, float focusZ) {
        Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();
        float maxDistanceSquared = 0f;
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                Vector4f farCorner = inverseViewProjection.transform(new Vector4f(x, z, 1f, 1f));
                if (Math.abs(farCorner.w) < 1.0e-6f) continue;
                float worldX = farCorner.x / farCorner.w;
                float worldZ = farCorner.z / farCorner.w;
                float dx = worldX - focusX;
                float dz = worldZ - focusZ;
                maxDistanceSquared = Math.max(maxDistanceSquared, dx * dx + dz * dz);
            }
        }
        float topLevelSize = CHUNK_SIZE * (1 << TOP_LOD);
        return Math.max(1, (int) Math.ceil(Math.sqrt(maxDistanceSquared) / topLevelSize) + 1);
    }

    public List<LodChunkMesh> visibleChunks() {
        return buffers[readIndex];
    }
}
