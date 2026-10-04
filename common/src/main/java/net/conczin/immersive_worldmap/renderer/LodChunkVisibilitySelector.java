package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.CircularChunkIterator;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

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
    private static final double LOD_HYSTERESIS = 0.05;
    private static final float POSITION_STEP = 1f / 16f;
    private static final float ANGLE_STEP = 1f / 64f;
    private static final long REFRESH_NANOS = 50_000_000L;

    private record View(String dimension, float x, float y, float z, float yaw, float pitch, float zoom, float aspect) {
    }

    private record CameraSnapshot(
            Matrix4f viewProjection,
            String dimension,
            double focusX,
            double focusZ,
            float zoom
    ) {
    }

    private final AtomicReference<CameraSnapshot> pendingSnapshot = new AtomicReference<>(null);

    private volatile Selection selection = new Selection(List.of());
    private View submittedView;
    private CameraSnapshot submittedSnapshot;
    private long submittedNanos;
    private int targetLod = -1;
    private String selectedDimension;

    public record Selection(List<Node> roots) {
        public List<LodChunkMesh> select(Predicate<LodChunkMesh> ready) {
            List<LodChunkMesh> selected = new ArrayList<>();
            for (Node root : roots) root.select(selected, ready);
            return selected;
        }
    }

    record Node(LodChunkMesh mesh, boolean preferred, List<Node> children) {
        private boolean select(List<LodChunkMesh> selected, Predicate<LodChunkMesh> ready) {
            boolean loaded = mesh != null && ready.test(mesh);
            if (preferred && loaded) {
                selected.add(mesh);
                return true;
            }
            int start = selected.size();
            if (children != null) {
                boolean covered = true;
                for (Node child : children) covered &= child.select(selected, ready);
                if (covered) return true;
            }
            if (loaded) {
                selected.subList(start, selected.size()).clear();
                selected.add(mesh);
                return true;
            }
            return false;
        }
    }

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
            selection = buildSelection(snapshot);
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

    private Selection buildSelection(CameraSnapshot snapshot) {
        List<Node> roots = new ArrayList<>();
        List<LodChunkMesh> targetMeshes = new ArrayList<>();
        Map<ChunkLodProcessor.CacheKey, LodChunkMesh> cachedMeshes = new HashMap<>();
        Set<ChunkLodProcessor.CacheKey> finerBranches = new HashSet<>();
        int targetLod = selectLod(snapshot);
        for (LodChunkMesh mesh : LodChunkMeshManager.INSTANCE.meshes()) {
            if (mesh.dimension.equals(snapshot.dimension())) {
                cachedMeshes.put(new ChunkLodProcessor.CacheKey(mesh.chunkX, mesh.chunkZ, mesh.dimension, mesh.lod), mesh);
                if (mesh.geometry() == null) continue;
                for (int lod = mesh.lod + 1; lod <= targetLod; lod++) {
                    int scale = 1 << (lod - mesh.lod);
                    finerBranches.add(new ChunkLodProcessor.CacheKey(Math.floorDiv(mesh.chunkX, scale),
                            Math.floorDiv(mesh.chunkZ, scale), mesh.dimension, lod));
                }
            }
        }

        FrustumIntersection frustum = new FrustumIntersection(snapshot.viewProjection());
        double radius = renderRadius(snapshot.zoom()) + 1;
        float rootWorldSize = worldSize(LodChunkData.MAX_LOD);
        int centerX = (int) Math.floor(snapshot.focusX() / rootWorldSize);
        int centerZ = (int) Math.floor(snapshot.focusZ() / rootWorldSize);
        int rootRadius = (int) Math.ceil(radius / rootWorldSize) + 1;

        CircularChunkIterator iterator = new CircularChunkIterator(centerX, centerZ, rootRadius);
        while (running && iterator.hasNext()) {
            int[] root = iterator.next();
            Node node = visit(root[0], root[1], LodChunkData.MAX_LOD, targetLod, radius, snapshot, frustum,
                    cachedMeshes, finerBranches, targetMeshes);
            if (node != null) roots.add(node);
        }

        Selection result = new Selection(List.copyOf(roots));
        List<LodChunkMesh> selected = result.select(LodChunkMesh::isLoaded);
        Comparator<LodChunkMesh> byDistance = Comparator.comparingDouble(mesh -> distanceSquared(mesh, snapshot));
        targetMeshes.sort(byDistance);
        selected.sort(byDistance);
        Set<LodChunkMesh> interested = new LinkedHashSet<>(targetMeshes);
        interested.addAll(selected);
        for (LodChunkMesh mesh : interested) {
            if (!running) return new Selection(List.of());
            LodChunkMeshManager.INSTANCE.retain(mesh);
            mesh.requestLoad();
        }
        updateTaskInterest(new ArrayList<>(interested));
        return result;
    }

    private int selectLod(CameraSnapshot snapshot) {
        if (!Objects.equals(selectedDimension, snapshot.dimension())) {
            selectedDimension = snapshot.dimension();
            targetLod = -1;
        }
        double base = CHUNK_SIZE * SUBDIVIDE_DISTANCE_FACTOR;
        if (targetLod < 0) {
            targetLod = 0;
            while (targetLod < LodChunkData.MAX_LOD && snapshot.zoom() >= Math.scalb(base, targetLod + 1)) {
                targetLod++;
            }
        }
        while (targetLod < LodChunkData.MAX_LOD && snapshot.zoom() >= Math.scalb(base, targetLod + 1) * (1 + LOD_HYSTERESIS)) {
            targetLod++;
        }
        while (targetLod > 0 && snapshot.zoom() < Math.scalb(base, targetLod) * (1 - LOD_HYSTERESIS)) {
            targetLod--;
        }
        return targetLod;
    }

    private Node visit(
            int chunkX,
            int chunkZ,
            int lod,
            int targetLod,
            double radius,
            CameraSnapshot snapshot,
            FrustumIntersection frustum,
            Map<ChunkLodProcessor.CacheKey, LodChunkMesh> cachedMeshes,
            Set<ChunkLodProcessor.CacheKey> finerBranches,
            List<LodChunkMesh> targetMeshes
    ) {
        float size = worldSize(lod);
        if (!isVisible(chunkX, chunkZ, size, radius, snapshot, frustum)) {
            return null;
        }

        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(chunkX, chunkZ, snapshot.dimension(), lod);
        LodChunkMesh mesh = cachedMeshes.get(key);
        if (lod == targetLod) {
            mesh = LodChunkMeshManager.INSTANCE.get(chunkX, chunkZ, lod, snapshot.dimension());
            targetMeshes.add(mesh);
        }
        if (lod <= targetLod && !finerBranches.contains(key)) {
            return new Node(mesh, true, null);
        }

        List<Node> children = new ArrayList<>(4);
        int childX = chunkX * 2;
        int childZ = chunkZ * 2;
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                Node child = visit(childX + x, childZ + z, lod - 1, targetLod, radius,
                        snapshot, frustum, cachedMeshes, finerBranches, targetMeshes);
                if (child != null) children.add(child);
            }
        }
        return new Node(mesh, lod <= targetLod, List.copyOf(children));
    }

    private boolean isVisible(int chunkX, int chunkZ, float size, double radius, CameraSnapshot snapshot, FrustumIntersection frustum) {
        float minX = chunkX * size;
        float minZ = chunkZ * size;
        double nearestX = Math.clamp(snapshot.focusX(), minX, minX + size);
        double nearestZ = Math.clamp(snapshot.focusZ(), minZ, minZ + size);
        double dx = nearestX - snapshot.focusX();
        double dz = nearestZ - snapshot.focusZ();
        float padding = POSITION_STEP + snapshot.zoom() * 0.001f;
        return dx * dx + dz * dz <= radius * radius
               && frustum.testAab(minX - padding, -padding, minZ - padding,
                minX + size + padding, CHUNK_HEIGHT + padding, minZ + size + padding);
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

    public void update(String dimension, float x, float y, float z, float yaw, float pitch, float zoom, float aspect) {
        View view = new View(dimension, round(x, POSITION_STEP), round(y, POSITION_STEP), round(z, POSITION_STEP),
                round(yaw, ANGLE_STEP), round(pitch, ANGLE_STEP), round(zoom, POSITION_STEP), aspect);
        long now = System.nanoTime();
        if (view.equals(submittedView)) {
            // Stationary views still need dirty meshes and cancelled loads retried.
            if (now - submittedNanos < REFRESH_NANOS) return;
        } else {
            submittedView = view;
            double yawRadians = Math.toRadians(view.yaw());
            double pitchRadians = Math.toRadians(view.pitch());
            float eyeX = view.x() - (float) (Math.sin(yawRadians) * Math.cos(pitchRadians)) * view.zoom();
            float eyeY = view.y() - (float) Math.sin(pitchRadians) * view.zoom();
            float eyeZ = view.z() - (float) (Math.cos(yawRadians) * Math.cos(pitchRadians)) * view.zoom();
            Matrix4f viewProjection = new Matrix4f().setPerspective((float) Math.toRadians(60), view.aspect(),
                            view.zoom() * 0.1f, view.zoom() * 10f)
                    .lookAt(eyeX, eyeY, eyeZ, view.x(), view.y(), view.z(), 0, 1, 0);
            submittedSnapshot = new CameraSnapshot(viewProjection, dimension, view.x(), view.z(), view.zoom());
        }
        submittedNanos = now;
        pendingSnapshot.set(submittedSnapshot);
    }

    private static float round(float value, float step) {
        return (float) (Math.rint(value / step) * step);
    }

    public Selection selection() {
        return selection;
    }
}
