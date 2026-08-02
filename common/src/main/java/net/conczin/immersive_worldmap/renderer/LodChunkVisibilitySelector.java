package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.util.CircularChunkIterator;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static final double SEA_LEVEL = 0;
    private static final double SUBDIVIDE_DISTANCE_FACTOR = 16;
    private static final double MIN_FORWARD_Y = 1.0e-4;
    private static final double LOG_2 = Math.log(2.0);

    private record CameraSnapshot(
            Matrix4f viewProjection,
            String dimension,
            double focusX,
            double focusZ,
            double cameraX,
            float cameraY,
            float cameraZ
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
        FrustumIntersection frustum = new FrustumIntersection(snapshot.viewProjection());
        int lod = selectLod(snapshot);
        float worldSize = CHUNK_SIZE * (1 << lod);
        int centerX = (int) Math.floor(snapshot.focusX() / worldSize);
        int centerZ = (int) Math.floor(snapshot.focusZ() / worldSize);

        CircularChunkIterator chunks = new CircularChunkIterator(centerX, centerZ, 16);
        while (chunks.hasNext()) {
            int[] chunk = chunks.next();
            int chunkX = chunk[0];
            int chunkZ = chunk[1];
            if (!frustum.testAab(chunkX * worldSize, 0f, chunkZ * worldSize,
                    (chunkX + 1) * worldSize, CHUNK_HEIGHT, (chunkZ + 1) * worldSize)) {
                continue;
            }
            LodChunkMesh mesh = LodChunkMeshManager.INSTANCE.get(chunkX, chunkZ, lod, snapshot.dimension());
            mesh.requestLoad();
            result.add(mesh);
        }
        updateTaskInterest(result);
        return result;
    }

    private int selectLod(CameraSnapshot snapshot) {
        double dx = snapshot.cameraX() - snapshot.focusX();
        double dy = snapshot.cameraY() - SEA_LEVEL;
        double dz = snapshot.cameraZ() - snapshot.focusZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int lod = (int) Math.floor(Math.log(Math.max(distance, 1f) / (CHUNK_SIZE * SUBDIVIDE_DISTANCE_FACTOR)) / LOG_2);
        return Math.clamp(lod, 0, TOP_LOD);
    }

    private void updateTaskInterest(List<LodChunkMesh> visible) {
        Set<ChunkLodProcessor.CacheKey> keys = new HashSet<>();
        for (LodChunkMesh mesh : visible) {
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ - 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ + 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX - 1, mesh.chunkZ, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX + 1, mesh.chunkZ, mesh.lod, mesh.dimension);
        }
        ChunkLodProcessor.discardQueuedTasksOutside(keys);
    }

    private void addTaskInterest(Set<ChunkLodProcessor.CacheKey> keys, int chunkX, int chunkZ, int lod, String dimension) {
        keys.add(new ChunkLodProcessor.CacheKey(chunkX, chunkZ, dimension, lod));
    }

    public void update(Matrix4f mv, Matrix4f proj, String dimension) {
        Matrix4f inverseView = new Matrix4f(mv).invert();
        Vector3f eye = inverseView.transformPosition(new Vector3f());
        Vector3f forward = inverseView.transformDirection(new Vector3f(0f, 0f, -1f));
        double distanceToSeaLevel = Math.abs(forward.y) > MIN_FORWARD_Y ? (SEA_LEVEL - eye.y) / forward.y : 0;
        double focusX = eye.x + forward.x * Math.max(0, distanceToSeaLevel);
        double focusZ = eye.z + forward.z * Math.max(0, distanceToSeaLevel);

        Matrix4f viewProjection = new Matrix4f(proj).mul(mv);
        pendingSnapshot.set(new CameraSnapshot(viewProjection, dimension, focusX, focusZ,
                eye.x, eye.y, eye.z));
    }

    public List<LodChunkMesh> visibleChunks() {
        return buffers[readIndex];
    }
}
