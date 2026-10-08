package net.conczin.immersive_worldmap.renderer;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.CircularChunkIterator;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class LodChunkVisibilitySelector {
    private static LodChunkVisibilitySelector INSTANCE;

    public static LodChunkVisibilitySelector get() {
        if (INSTANCE == null) {
            INSTANCE = new LodChunkVisibilitySelector();
        }
        return INSTANCE;
    }

    public static void reset() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    private static final int CHUNK_SIZE = 16;

    private static final double SUBDIVIDE_DISTANCE_FACTOR = 12;
    private static final double RENDER_DISTANCE = 48;
    private static final double LOD_HYSTERESIS = 0.05;
    private static final float POSITION_STEP = 1f / 16f;
    private static final float ANGLE_STEP = 1f / 64f;
    private static final long REFRESH_NANOS = 200_000_000L;

    private record View(String dimension, int height, float x, float y, float z, float yaw, float pitch, float zoom,
                        float aspect, float radius) {
    }

    private record CameraSnapshot(
            Matrix4f viewProjection,
            String dimension,
            int height,
            double focusX,
            double focusZ,
            float zoom,
            float radius
    ) {
    }

    private final AtomicReference<CameraSnapshot> pendingSnapshot = new AtomicReference<>(null);

    private volatile LodChunkPageManager.Plan selection = LodChunkPageManager.Plan.EMPTY;
    private View submittedView;
    private CameraSnapshot submittedSnapshot;
    private long submittedNanos;
    private int targetLod = -1;
    private String selectedDimension;

    private final Set<ChunkLodProcessor.CacheKey> taskInterest = new ObjectOpenHashSet<>();

    private final Thread worker;
    private volatile boolean running = true;

    private LodChunkVisibilitySelector() {
        worker = new Thread(this::workerLoop, "ImmersiveWorldmap-LodSelection");
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler((thread, error) -> {
            running = false;
            ThreadPoolUtil.crash(thread, error);
        });
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
            CameraSnapshot snapshot = pendingSnapshot.getAndSet(null);
            if (snapshot == null) {
                LockSupport.park();
            } else {
                selection = buildSelection(snapshot);
            }
        }
    }

    private LodChunkPageManager.Plan buildSelection(CameraSnapshot snapshot) {
        try {
            List<LodChunkMesh> selected = new ArrayList<>();
            List<LodChunkMesh> targetMeshes = new ArrayList<>();
            int targetLod = selectLod(snapshot);
            FrustumIntersection frustum = new FrustumIntersection(snapshot.viewProjection());
            double radius = snapshot.radius() + 1;
            float rootWorldSize = worldSize(LodChunkData.MAX_LOD);
            int centerX = (int) Math.floor(snapshot.focusX() / rootWorldSize);
            int centerZ = (int) Math.floor(snapshot.focusZ() / rootWorldSize);
            int rootRadius = (int) Math.ceil(radius / rootWorldSize) + 1;

            CircularChunkIterator iterator = new CircularChunkIterator(centerX, centerZ, rootRadius);
            while (running && iterator.hasNext()) {
                int[] root = iterator.next();
                visit(root[0], root[1], LodChunkData.MAX_LOD, targetLod, radius, snapshot, frustum,
                        targetMeshes, selected);
            }

            Comparator<LodChunkMesh> byDistance = Comparator.comparingDouble(mesh -> distanceSquared(mesh, snapshot));
            targetMeshes.sort(byDistance);
            Set<LodChunkMesh> interested = new ObjectLinkedOpenHashSet<>(targetMeshes);
            interested.addAll(selected);
            LodChunkMeshManager.INSTANCE.setViewport(interested);

            for (LodChunkMesh mesh : interested) {
                if (!running) {
                    return LodChunkPageManager.Plan.EMPTY;
                }
                LodChunkMeshManager.INSTANCE.retain(mesh);
                mesh.requestLoad();
            }

            updateTaskInterest(interested);

            return LodChunkPageManager.INSTANCE.prepare(interested, selected);
        } finally {
            taskInterest.clear();
        }
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

    private boolean visit(
            int chunkX,
            int chunkZ,
            int lod,
            int targetLod,
            double radius,
            CameraSnapshot snapshot,
            FrustumIntersection frustum,
            List<LodChunkMesh> targetMeshes,
            List<LodChunkMesh> selected
    ) {
        float size = worldSize(lod);
        if (!isVisible(chunkX, chunkZ, size, radius, snapshot, frustum)) {
            return true;
        }

        ChunkLodProcessor.CacheKey key = new ChunkLodProcessor.CacheKey(chunkX, chunkZ, snapshot.dimension(), lod);
        LodChunkMesh mesh;
        if (lod == targetLod) {
            mesh = LodChunkMeshManager.INSTANCE.get(chunkX, chunkZ, lod, snapshot.dimension());
            targetMeshes.add(mesh);
        } else {
            mesh = LodChunkMeshManager.INSTANCE.find(key);
        }

        boolean loaded = mesh != null && LodChunkPageManager.INSTANCE.isDrawable(mesh);
        if (lod <= targetLod && loaded) {
            selected.add(mesh);
            return true;
        }

        int start = selected.size();
        if (lod > targetLod || LodChunkMeshManager.INSTANCE.hasFinerGeometry(key)) {
            boolean covered = true;
            int childX = chunkX * 2;
            int childZ = chunkZ * 2;
            for (int x = 0; x < 2; x++) {
                for (int z = 0; z < 2; z++) {
                    covered &= visit(childX + x, childZ + z, lod - 1, targetLod, radius,
                            snapshot, frustum, targetMeshes, selected);
                }
            }
            if (covered) return true;
        }

        if (loaded) {
            // An incomplete finer cut keeps its resident coarse fallback.
            selected.subList(start, selected.size()).clear();
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
        float padding = POSITION_STEP + snapshot.zoom() * 0.001f;
        return dx * dx + dz * dz <= radius * radius
               && frustum.testAab(minX - padding, -padding, minZ - padding,
                minX + size + padding, snapshot.height() + padding, minZ + size + padding);
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

    private void updateTaskInterest(Collection<LodChunkMesh> visible) {
        for (LodChunkMesh mesh : visible) {
            taskInterest.add(mesh.key());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        taskInterest.add(new ChunkLodProcessor.CacheKey(mesh.chunkX + dx, mesh.chunkZ + dz, mesh.dimension, mesh.lod));
                    }
                }
            }
        }
        ChunkLodProcessor.discardQueuedTasksOutside(taskInterest);
    }

    public record RenderRange(float start, float end) {
    }

    public static RenderRange renderRange(float zoom, float pitchDegrees, float heightAboveTerrain) {
        double pitch = Math.toRadians(-pitchDegrees);
        float heightOffset = heightAboveTerrain / (float) Math.sin(pitch);
        float horizonOffset = heightOffset * (float) Math.cos(pitch);
        float radius = (float) Math.ceil((zoom + heightOffset) / CHUNK_SIZE * RENDER_DISTANCE);
        // Compensate the fixed height gap so the far fade edge keeps the same viewing angle at close zoom.
        return new RenderRange(radius * 0.75f + horizonOffset, radius + horizonOffset);
    }

    public void update(String dimension, int height, float x, float y, float z, float yaw, float pitch, float zoom, float aspect, float radius) {
        View view = new View(dimension, height, round(x, POSITION_STEP), round(y, POSITION_STEP), round(z, POSITION_STEP),
                round(yaw, ANGLE_STEP), round(pitch, ANGLE_STEP), round(zoom, POSITION_STEP), aspect, radius);
        long now = System.nanoTime();
        if (view.equals(submittedView)) {
            // Stationary views still need dirty meshes and canceled loads retried.
            if (now - submittedNanos < REFRESH_NANOS) return;
        } else {
            submittedView = view;
            double yawRadians = Math.toRadians(view.yaw());
            double pitchRadians = Math.toRadians(view.pitch());
            float eyeX = view.x() - (float) (Math.sin(yawRadians) * Math.cos(pitchRadians)) * view.zoom();
            float eyeY = view.y() - (float) Math.sin(pitchRadians) * view.zoom();
            float eyeZ = view.z() - (float) (Math.cos(yawRadians) * Math.cos(pitchRadians)) * view.zoom();
            Matrix4f viewProjection = new Matrix4f().setPerspective((float) Math.toRadians(60), view.aspect(),
                            view.zoom() * 0.1f, Math.max(view.zoom() * 10f, view.radius() * 2f))
                    .lookAt(eyeX, eyeY, eyeZ, view.x(), view.y(), view.z(), 0, 1, 0);
            submittedSnapshot = new CameraSnapshot(viewProjection, dimension, height, view.x(), view.z(), view.zoom(), view.radius());
        }
        submittedNanos = now;
        pendingSnapshot.set(submittedSnapshot);
        LockSupport.unpark(worker);
    }

    private static float round(float value, float step) {
        return (float) (Math.rint(value / step) * step);
    }

    public LodChunkPageManager.Plan selection() {
        return selection;
    }
}
