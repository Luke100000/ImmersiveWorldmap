package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.CircularChunkIterator;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;
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

    private static final float CHUNK_HEIGHT = 384f;
    private static final int CHUNK_SIZE = 16;

    private static final double SUBDIVIDE_DISTANCE_FACTOR = 12;
    private static final double RENDER_DISTANCE = 48;
    private static final double LOG_2 = Math.log(2.0);

    private record CameraSnapshot(
            Matrix4f viewProjection,
            String dimension,
            double focusX,
            double focusZ,
            float zoom
    ) {
    }

    private final AtomicReference<CameraSnapshot> pendingSnapshot = new AtomicReference<>(null);

    private volatile List<LodChunkMesh> visible = List.of();

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
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        while (running) {
            CameraSnapshot snapshot = waitForSnapshot();
            if (snapshot == null) continue;
            visible = buildVisibleList(snapshot);
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
        List<LodChunkMesh> selected = new ArrayList<>();
        List<LodChunkMesh> targetMeshes = new ArrayList<>();
        Map<ChunkLodProcessor.CacheKey, LodChunkMesh> loadedMeshes = new HashMap<>();
        for (LodChunkMesh mesh : LodChunkMeshManager.INSTANCE.meshes()) {
            if (mesh.isLoaded()) {
                loadedMeshes.put(new ChunkLodProcessor.CacheKey(mesh.chunkX, mesh.chunkZ, mesh.dimension, mesh.lod), mesh);
            }
        }

        FrustumIntersection frustum = new FrustumIntersection(snapshot.viewProjection());
        int targetLod = selectLod(snapshot);
        double radius = renderRadius(snapshot.zoom());
        float rootWorldSize = worldSize(LodChunkData.MAX_LOD);
        int centerX = (int) Math.floor(snapshot.focusX() / rootWorldSize);
        int centerZ = (int) Math.floor(snapshot.focusZ() / rootWorldSize);
        int rootRadius = (int) Math.ceil(radius / rootWorldSize) + 1;

        CircularChunkIterator roots = new CircularChunkIterator(centerX, centerZ, rootRadius);
        while (running && roots.hasNext()) {
            int[] root = roots.next();
            visit(root[0], root[1], LodChunkData.MAX_LOD, targetLod, radius, snapshot, frustum,
                    loadedMeshes, targetMeshes, selected);
        }

        Comparator<LodChunkMesh> byDistance = Comparator.comparingDouble(mesh -> distanceSquared(mesh, snapshot));
        targetMeshes.sort(byDistance);
        selected.sort(byDistance);
        Set<LodChunkMesh> interested = new LinkedHashSet<>(targetMeshes);
        interested.addAll(selected);
        for (LodChunkMesh mesh : interested) {
            if (!running) return List.of();
            LodChunkMeshManager.INSTANCE.retain(mesh);
            mesh.requestLoad();
        }
        updateTaskInterest(new ArrayList<>(interested));
        return selected;
    }

    private int selectLod(CameraSnapshot snapshot) {
        int lod = (int) Math.floor(Math.log(Math.max(snapshot.zoom(), 1f) / (CHUNK_SIZE * SUBDIVIDE_DISTANCE_FACTOR)) / LOG_2);
        return Math.clamp(lod, 0, LodChunkData.MAX_LOD);
    }

    private boolean visit(
            int chunkX,
            int chunkZ,
            int lod,
            int targetLod,
            double radius,
            CameraSnapshot snapshot,
            FrustumIntersection frustum,
            Map<ChunkLodProcessor.CacheKey, LodChunkMesh> loadedMeshes,
            List<LodChunkMesh> targetMeshes,
            List<LodChunkMesh> selected
    ) {
        float size = worldSize(lod);
        if (!isVisible(chunkX, chunkZ, size, radius, snapshot, frustum)) {
            return true;
        }

        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(chunkX, chunkZ, snapshot.dimension(), lod);
        LodChunkMesh mesh = loadedMeshes.get(key);
        if (lod == targetLod) {
            mesh = LodChunkMeshManager.INSTANCE.get(chunkX, chunkZ, lod, snapshot.dimension());
            targetMeshes.add(mesh);
        }
        if (lod <= targetLod && mesh != null && mesh.isLoaded()) {
            selected.add(mesh);
            return true;
        }
        if (lod == 0) {
            return false;
        }

        int selectedStart = selected.size();
        boolean covered = true;
        int childX = chunkX * 2;
        int childZ = chunkZ * 2;
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                covered &= visit(childX + x, childZ + z, lod - 1, targetLod, radius,
                        snapshot, frustum, loadedMeshes, targetMeshes, selected);
            }
        }
        if (covered) {
            return true;
        }
        if (mesh != null && mesh.isLoaded()) {
            selected.subList(selectedStart, selected.size()).clear();
            selected.add(mesh);
            return true;
        }
        return false;
    }

    private boolean isVisible(int chunkX, int chunkZ, float size, double radius, CameraSnapshot snapshot, FrustumIntersection frustum) {
        float minX = chunkX * size;
        float minZ = chunkZ * size;
        double nearestX = Math.clamp(snapshot.focusX(), minX, minX + size);
        double nearestZ = Math.clamp(snapshot.focusZ(), minZ, minZ + size);
        double dx = nearestX - snapshot.focusX();
        double dz = nearestZ - snapshot.focusZ();
        return dx * dx + dz * dz <= radius * radius
               && frustum.testAab(minX, 0f, minZ, minX + size, CHUNK_HEIGHT, minZ + size);
    }

    private float worldSize(int lod) {
        return CHUNK_SIZE * (1 << lod);
    }

    private double distanceSquared(LodChunkMesh mesh, CameraSnapshot snapshot) {
        float size = worldSize(mesh.lod);
        double dx = (mesh.chunkX + 0.5) * size - snapshot.focusX();
        double dz = (mesh.chunkZ + 0.5) * size - snapshot.focusZ();
        return dx * dx + dz * dz;
    }

    private void updateTaskInterest(List<LodChunkMesh> visible) {
        Set<ChunkLodProcessor.CacheKey> keys = new HashSet<>();
        for (LodChunkMesh mesh : visible) {
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ - 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX, mesh.chunkZ + 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX - 1, mesh.chunkZ, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX + 1, mesh.chunkZ, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX - 1, mesh.chunkZ - 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX + 1, mesh.chunkZ - 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX - 1, mesh.chunkZ + 1, mesh.lod, mesh.dimension);
            addTaskInterest(keys, mesh.chunkX + 1, mesh.chunkZ + 1, mesh.lod, mesh.dimension);
        }
        ChunkLodProcessor.discardQueuedTasksOutside(keys);
    }

    private void addTaskInterest(Set<ChunkLodProcessor.CacheKey> keys, int chunkX, int chunkZ, int lod, String dimension) {
        keys.add(new ChunkLodProcessor.CacheKey(chunkX, chunkZ, dimension, lod));
    }

    public static float renderRadius(float zoom) {
        return (float) Math.ceil(zoom / CHUNK_SIZE * RENDER_DISTANCE);
    }

    public void update(Matrix4f mv, Matrix4f proj, String dimension, float focusX, float focusZ, float zoom) {
        Matrix4f viewProjection = new Matrix4f(proj).mul(mv);
        pendingSnapshot.set(new CameraSnapshot(viewProjection, dimension, focusX, focusZ, zoom));
    }

    public List<LodChunkMesh> visibleChunks() {
        return visible;
    }
}
