package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.conczin.immersive_worldmap.screen.MapBackgroundTexture;
import net.conczin.immersive_worldmap.util.PooledBytes;
import net.conczin.immersive_worldmap.util.PriorityThreadPoolExecutor;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LodChunkPageManager {
    public static final int PAGE_SIZE = 8;
    public static final String SHADER_NAME = "immersive_worldmap_map";

    private static final int SLOTS = PAGE_SIZE * PAGE_SIZE;
    private static final int VERTEX_SIZE = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
    private static final int QUAD_SIZE = 4 * VERTEX_SIZE;
    private static final long PRUNE_INTERVAL_NANOS = 200_000_000L;

    public static final LodChunkPageManager INSTANCE = new LodChunkPageManager();

    private final Map<PageKey, Page> pages = new ConcurrentHashMap<>();
    private final Set<Page> inputPages = new ObjectLinkedOpenHashSet<>();
    private final PriorityThreadPoolExecutor buildExecutor =
            ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap-PageBuilder", 1);

    private final Matrix4f localMv = new Matrix4f();
    private final IntBuffer counts = BufferUtils.createIntBuffer(SLOTS);
    private final IntBuffer bases = BufferUtils.createIntBuffer(SLOTS);

    // Quad indices start at zero for every mesh and only the base vertex varies.
    private final PointerBuffer offsets = BufferUtils.createPointerBuffer(SLOTS);
    private long gpuBytes;
    private int uploadCursor;
    private volatile boolean active;
    private volatile long assemblingBytes;

    private long lastPruneNanos;

    private LodChunkPageManager() {
    }

    public record Plan(Map<Page, List<LodChunkMesh>> drawPages, List<Page> neededPages) {
        static final Plan EMPTY = new Plan(Map.of(), List.of());
    }

    public int getPendingTaskCount() {
        return buildExecutor.getActiveCount() + buildExecutor.getQueue().size()
               + (int) pages.values().stream().filter(page -> page.ready != null).count();
    }

    synchronized Plan prepare(Collection<LodChunkMesh> interested, List<LodChunkMesh> visible) {
        if (!active) return Plan.EMPTY;
        try {
            long now = System.nanoTime();
            for (LodChunkMesh mesh : interested) {
                Page page = pages.computeIfAbsent(key(mesh), Page::new);
                page.lastUsedNanos = now;
                inputPages.add(page);
                page.inputs.add(mesh);
            }
            for (Page page : inputPages) {
                page.update(page.inputs);
            }

            Map<Page, List<LodChunkMesh>> batches = new LinkedHashMap<>();
            for (LodChunkMesh mesh : visible) {
                Page page = pages.get(key(mesh));
                batches.computeIfAbsent(page, ignored -> new ArrayList<>()).add(mesh);
            }
            batches.replaceAll((page, meshes) -> List.copyOf(meshes));
            return new Plan(Collections.unmodifiableMap(batches), List.copyOf(inputPages));
        } finally {
            for (Page page : inputPages) {
                page.inputs.clear();
            }
            inputPages.clear();
        }
    }

    public void draw(Plan plan, Matrix4f mv, Matrix4f proj, float focusX, float focusZ,
                     LodChunkVisibilitySelector.RenderRange range) {
        long now = System.nanoTime();
        for (Page page : plan.neededPages) {
            page.lastUsedNanos = now;
        }
        uploadReady(plan);

        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = minecraft.gameRenderer.getShader(SHADER_NAME);
        if (shader != null) {
            int width = minecraft.getWindow().getGuiScaledWidth();
            int height = minecraft.getWindow().getGuiScaledHeight();
            int side = (int) Math.ceil(Math.hypot(width, height));
            shader.setSampler("BackgroundTexture", minecraft.getTextureManager().getTexture(MapBackgroundTexture.get()));
            shader.safeGetUniform("BackgroundUv").set(
                    (float) width / side, (float) height / side,
                    -(float) ((width - side) / 2) / side, -(float) ((height - side) / 2) / side);
            shader.safeGetUniform("FadeStart").set(range.start());
            shader.safeGetUniform("FadeEnd").set(range.end());
            plan.drawPages.forEach((page, meshes) -> page.draw(meshes, mv, proj, shader, focusX, focusZ));
        }
        pruneUnusedPages(now);
    }

    boolean isDrawable(LodChunkMesh mesh) {
        Page page = pages.get(key(mesh));
        PageData uploaded = page == null ? null : page.uploaded;
        if (uploaded != null && uploaded.snapshot.chunks[slot(mesh)] == mesh) return true;
        PooledBytes geometry = mesh.geometry();
        return geometry != null && geometry.length() == 0;
    }

    public synchronized void clear() {
        for (Page page : pages.values()) {
            page.close();
        }
        pages.clear();
        buildExecutor.getQueue().clear();
        gpuBytes = 0;
        uploadCursor = 0;
        lastPruneNanos = 0;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
        if (!active) {
            clear();
        }
    }

    public long getGpuBytes() {
        return gpuBytes;
    }

    public long getEstimatedRamBytes() {
        return pages.size() * SLOTS * 64L + assemblingBytes + pages.values().stream().mapToLong(Page::readyBytes).sum();
    }

    private static int slot(LodChunkMesh mesh) {
        return Math.floorMod(mesh.chunkX, PAGE_SIZE) * PAGE_SIZE + Math.floorMod(mesh.chunkZ, PAGE_SIZE);
    }

    private static PageKey key(LodChunkMesh chunk) {
        return new PageKey(chunk.dimension, chunk.lod,
                Math.floorDiv(chunk.chunkX, PAGE_SIZE), Math.floorDiv(chunk.chunkZ, PAGE_SIZE));
    }

    private PageBuild assemble(Snapshot snapshot) {
        int[] baseVertices = new int[SLOTS];
        int[] indexCounts = new int[SLOTS];
        int totalBytes = 0;
        int maxIndexCount = 0;
        int maxVertexCount = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            PooledBytes geometry = snapshot.geometry[slot];
            baseVertices[slot] = totalBytes / VERTEX_SIZE;
            if (geometry == null || geometry.length() == 0) continue;
            totalBytes = Math.addExact(totalBytes, geometry.length());
            int vertices = geometry.length() / VERTEX_SIZE;
            int indices = geometry.length() / QUAD_SIZE * 6;
            maxVertexCount = Math.max(maxVertexCount, vertices);
            maxIndexCount = Math.max(maxIndexCount, indices);
            indexCounts[slot] = indices;
        }

        // Multi-draw reuses quad indices from zero with a different base vertex per chunk.
        PageData data = new PageData(snapshot, baseVertices, indexCounts, totalBytes, new MeshData.DrawState(
                DefaultVertexFormat.POSITION_COLOR, totalBytes / VERTEX_SIZE, maxIndexCount,
                VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(maxVertexCount)));
        if (totalBytes == 0) return new PageBuild(data, null);
        assemblingBytes = totalBytes;
        ByteBufferBuilder buffer = new ByteBufferBuilder(totalBytes);
        try {
            ByteBuffer target = MemoryUtil.memByteBuffer(buffer.reserve(totalBytes), totalBytes);
            for (PooledBytes geometry : snapshot.geometry) {
                if (geometry != null) geometry.copyTo(target);
            }
            return new PageBuild(data, buffer);
        } catch (Throwable error) {
            buffer.close();
            throw error;
        }
    }

    private void uploadReady(Plan plan) {
        for (int checked = 0; checked < plan.neededPages.size(); checked++) {
            uploadCursor %= plan.neededPages.size();
            Page page = plan.neededPages.get(uploadCursor++);
            if (page.upload(plan.drawPages.getOrDefault(page, List.of()))) return;
        }
    }

    private synchronized void pruneUnusedPages(long now) {
        if (now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
        lastPruneNanos = now;
        for (Page page : pages.values()) {
            if (now - page.lastUsedNanos >= PRUNE_INTERVAL_NANOS && pages.remove(page.key, page)) {
                page.close();
            }
        }
    }

    private record PageKey(String dimension, int lod, int x, int z) {
        @Override
        public int hashCode() {
            long coordinates = ((long) x << 32) | (z & 0xffffffffL);
            int hash = HashCommon.long2int(HashCommon.mix(coordinates));
            return 31 * (31 * hash + Objects.hashCode(dimension)) + lod;
        }
    }

    private record Snapshot(long revision, LodChunkMesh[] chunks, PooledBytes[] geometry) {
        boolean matches(Snapshot other) {
            if (other == null || revision != other.revision) return false;
            for (int slot = 0; slot < SLOTS; slot++) {
                if (chunks[slot] != other.chunks[slot] || geometry[slot] != other.geometry[slot]) {
                    return false;
                }
            }
            return true;
        }
    }

    private record PageData(
            Snapshot snapshot,
            int[] baseVertices,
            int[] indexCounts,
            int bytes,
            MeshData.DrawState drawState
    ) {
    }

    private record PageBuild(PageData data, ByteBufferBuilder buffer) implements AutoCloseable {

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private final class Page {
        final PageKey key;
        final List<LodChunkMesh> inputs = new ArrayList<>();
        volatile long lastUsedNanos;
        boolean building;
        boolean closed;
        volatile PageData uploaded;
        volatile PageBuild ready;
        VertexBuffer vertexBuffer;

        Page(PageKey key) {
            this.key = key;
        }

        long readyBytes() {
            PageBuild build = ready;
            return build == null ? 0 : build.data.bytes;
        }

        synchronized void update(List<LodChunkMesh> meshes) {
            if (closed || building || ready != null) return;

            LodChunkMesh[] chunks = new LodChunkMesh[SLOTS];
            PooledBytes[] geometry = new PooledBytes[SLOTS];
            long revision = 0;
            boolean hasGeometry = false;
            for (LodChunkMesh mesh : meshes) {
                int slot = slot(mesh);
                long meshRevision = mesh.copyGeometry(geometry, slot);
                if (geometry[slot] == null && uploaded != null && uploaded.snapshot.chunks[slot] == mesh) {
                    geometry[slot] = uploaded.snapshot.geometry[slot];
                }
                if (geometry[slot] == null) continue;
                chunks[slot] = mesh;
                hasGeometry = true;
                revision += HashCommon.mix(meshRevision ^ HashCommon.mix((long) slot));
            }

            Snapshot snapshot = new Snapshot(revision, chunks, geometry);
            if (snapshot.matches(uploaded == null ? null : uploaded.snapshot)) return;
            if (!hasGeometry && uploaded == null) return;

            building = true;
            buildExecutor.execute(() -> build(snapshot));
        }

        private void build(Snapshot snapshot) {
            PageBuild build;
            try {
                build = assemble(snapshot);
            } finally {
                assemblingBytes = 0;
            }
            synchronized (this) {
                building = false;
                if (closed) {
                    build.close();
                } else {
                    ready = build;
                }
            }
        }

        synchronized boolean upload(List<LodChunkMesh> selected) {
            PageBuild build = ready;
            if (build == null) return false;

            ready = null;
            try (build) {
                // Keep the uploaded cut covered while replacing its page buffer.
                for (LodChunkMesh mesh : selected) {
                    int slot = slot(mesh);
                    if (uploaded != null && uploaded.snapshot.chunks[slot] == mesh
                        && build.data.snapshot.chunks[slot] != mesh) {
                        return false;
                    }
                }

                if (build.buffer != null) {
                    if (vertexBuffer == null) {
                        vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    }
                    try {
                        vertexBuffer.bind();
                        vertexBuffer.upload(new MeshData(Objects.requireNonNull(build.buffer.build()), build.data.drawState));
                    } catch (Throwable error) {
                        close();
                        throw error;
                    } finally {
                        VertexBuffer.unbind();
                    }
                } else if (vertexBuffer != null) {
                    vertexBuffer.close();
                    vertexBuffer = null;
                }

                gpuBytes += build.data.bytes - (uploaded == null ? 0 : uploaded.bytes);
                uploaded = build.data;
            }
            return true;
        }

        void draw(List<LodChunkMesh> selected, Matrix4f mv, Matrix4f proj, ShaderInstance shader, float focusX, float focusZ) {
            if (vertexBuffer == null || closed) return;
            counts.clear();
            bases.clear();

            for (LodChunkMesh chunk : selected) {
                int slot = slot(chunk);
                if (uploaded.snapshot.chunks[slot] != chunk) continue;
                PooledBytes geometry = chunk.geometry();
                if (geometry != null && geometry.length() == 0) continue;
                int indexCount = uploaded.indexCounts[slot];
                if (indexCount == 0) continue;
                counts.put(indexCount);
                bases.put(uploaded.baseVertices[slot]);
            }

            if (counts.position() == 0) return;
            counts.flip();
            bases.flip();

            float scale = 1 << key.lod;
            shader.safeGetUniform("MapOffset").set(
                    key.x * PAGE_SIZE * 16f * scale - focusX, key.z * PAGE_SIZE * 16f * scale - focusZ);
            shader.safeGetUniform("MapScale").set(scale);
            localMv.set(mv)
                    .translate(key.x * PAGE_SIZE * 16f * scale, 0, key.z * PAGE_SIZE * 16f * scale)
                    .scale(scale, scale, scale);

            vertexBuffer.bind();

            try {
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                indices.bind(uploaded.drawState.indexCount());
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS, localMv, proj, Minecraft.getInstance().getWindow());
                shader.apply();
                try {
                    GL32.glMultiDrawElementsBaseVertex(GL11.GL_TRIANGLES, counts, indices.type().asGLType, offsets, bases);
                } finally {
                    shader.clear();
                }
            } finally {
                VertexBuffer.unbind();
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            if (ready != null) {
                ready.close();
                ready = null;
            }
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            gpuBytes -= uploaded == null ? 0 : uploaded.bytes;
            uploaded = null;
        }
    }
}
