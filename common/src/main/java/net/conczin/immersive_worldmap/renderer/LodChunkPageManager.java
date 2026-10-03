package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.screen.MapBackgroundTexture;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class LodChunkPageManager {
    public static final int PAGE_SIZE = 16;
    public static final String SHADER_NAME = "immersive_worldmap_map";

    private static final int SLOTS = PAGE_SIZE * PAGE_SIZE;
    private static final int VERTEX_SIZE = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
    private static final int QUAD_SIZE = 4 * VERTEX_SIZE;
    private static final int MAX_UPLOADS_PER_FRAME = 1;
    private static final int MAX_PAGES = 256;
    private static final long MAX_GPU_BYTES = 128L * 1024 * 1024;
    public static final LodChunkPageManager INSTANCE = new LodChunkPageManager();

    private final Map<PageKey, Page> pages = new ConcurrentHashMap<>();
    private final PriorityThreadPoolExecutor buildExecutor =
            ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap-PageBuilder", 1);
    private final ConcurrentLinkedQueue<Page> uploadQueue = new ConcurrentLinkedQueue<>();
    private long gpuBytes;
    private long frame;
    private long generation;

    private LodChunkPageManager() {
    }

    synchronized long generation() {
        return generation;
    }

    synchronized void offer(LodChunkMesh chunk, byte[] geometry) {
        if (chunk.pageGeneration() != generation || chunk.geometry() != geometry) return;
        if (geometry.length == 0 && !pages.containsKey(key(chunk))) {
            chunk.onPageUploaded(geometry);
            return;
        }

        PageKey key = key(chunk);
        while (true) {
            Page page = pages.computeIfAbsent(key, Page::new);
            if (page.offer(chunk, geometry)) return;
            pages.remove(key, page);
        }
    }

    public void draw(List<LodChunkMesh> visible, Matrix4f mv, Matrix4f proj, float focusX, float focusZ, float radius) {
        frame++;
        uploadReady();

        Map<Page, List<LodChunkMesh>> batches = new HashMap<>();
        for (LodChunkMesh chunk : visible) {
            if (chunk.pageGeneration() != generation) continue;
            Page page = pages.get(key(chunk));
            if (page != null) {
                batches.computeIfAbsent(page, ignored -> new ArrayList<>()).add(chunk);
            }
        }

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
            shader.safeGetUniform("FadeStart").set(radius * 0.75f);
            shader.safeGetUniform("FadeEnd").set(radius);
            for (Map.Entry<Page, List<LodChunkMesh>> entry : batches.entrySet()) {
                Page page = entry.getKey();
                page.lastDrawFrame = frame;
                page.draw(entry.getValue(), mv, proj, shader, focusX, focusZ);
            }
        }
        trim();
    }

    public synchronized void clear() {
        generation++;
        for (Page page : pages.values()) {
            page.close();
        }
        pages.clear();
        buildExecutor.getQueue().clear();
        uploadQueue.clear();
        gpuBytes = 0;
    }

    private static PageKey key(LodChunkMesh chunk) {
        return new PageKey(chunk.dimension, chunk.lod,
                Math.floorDiv(chunk.chunkX, PAGE_SIZE), Math.floorDiv(chunk.chunkZ, PAGE_SIZE));
    }

    private void buildPage(Page page) {
        Snapshot snapshot = page.snapshot();
        if (snapshot == null) return;

        PageBuild build;
        try {
            build = assemble(snapshot);
        } catch (Throwable error) {
            ImmersiveWorldmap.LOGGER.error("Failed to build LOD page {}", page.key, error);
            page.buildFailed(snapshot.revision);
            return;
        }
        page.built(build);
    }

    private static PageBuild assemble(Snapshot snapshot) {
        int[] baseVertices = new int[SLOTS];
        int[] indexCounts = new int[SLOTS];
        int totalBytes = 0;
        int maxIndexCount = 0;
        int maxVertexCount = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            byte[] geometry = snapshot.geometry[slot];
            baseVertices[slot] = totalBytes / VERTEX_SIZE;
            if (geometry == null || geometry.length == 0) continue;
            totalBytes = Math.addExact(totalBytes, geometry.length);
            int vertices = geometry.length / VERTEX_SIZE;
            int indices = geometry.length / QUAD_SIZE * 6;
            maxVertexCount = Math.max(maxVertexCount, vertices);
            maxIndexCount = Math.max(maxIndexCount, indices);
            indexCounts[slot] = indices;
        }

        ByteBufferBuilder buffer = null;
        MeshData mesh = null;
        if (totalBytes > 0) {
            buffer = new ByteBufferBuilder(totalBytes);
            try {
                ByteBuffer target = MemoryUtil.memByteBuffer(buffer.reserve(totalBytes), totalBytes);
                for (byte[] geometry : snapshot.geometry) {
                    if (geometry != null && geometry.length > 0) target.put(geometry);
                }
                // Multi-draw reuses the quad indices from zero with a different base vertex per chunk.
                mesh = new MeshData(Objects.requireNonNull(buffer.build()), new MeshData.DrawState(
                        DefaultVertexFormat.POSITION_COLOR, totalBytes / VERTEX_SIZE, maxIndexCount,
                        VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(maxVertexCount)));
            } catch (Throwable error) {
                buffer.close();
                throw error;
            }
        }
        return new PageBuild(snapshot, baseVertices, indexCounts, maxIndexCount, totalBytes, buffer, mesh);
    }

    private void uploadReady() {
        for (int uploaded = 0; uploaded < MAX_UPLOADS_PER_FRAME; ) {
            Page page = uploadQueue.poll();
            if (page == null) return;
            PageBuild build = page.takeReady();
            if (build == null) continue;
            uploaded++;
            try (build) {
                long oldBytes = page.gpuBytes;
                page.install(build);
                gpuBytes += page.gpuBytes - oldBytes;
            } catch (Throwable error) {
                ImmersiveWorldmap.LOGGER.error("Failed to upload LOD page {}", page.key, error);
                page.buildFailed(build.snapshot.revision);
            }
        }
    }

    private void trim() {
        while (pages.size() > MAX_PAGES || gpuBytes > MAX_GPU_BYTES) {
            Page oldest = pages.values().stream()
                    .filter(page -> page.lastDrawFrame != frame)
                    .min(Comparator.comparingLong(page -> page.lastDrawFrame))
                    .orElse(null);
            if (oldest == null || !pages.remove(oldest.key, oldest)) return;
            gpuBytes -= oldest.gpuBytes;
            oldest.close();
        }
    }

    private record PageKey(String dimension, int lod, int x, int z) {
    }

    private record Snapshot(long revision, LodChunkMesh[] chunks, byte[][] geometry) {
    }

    private record PageBuild(
            Snapshot snapshot,
            int[] baseVertices,
            int[] indexCounts,
            int maxIndexCount,
            int bytes,
            ByteBufferBuilder buffer,
            MeshData mesh
    ) implements AutoCloseable {

        @Override
        public void close() {
            if (mesh != null) mesh.close();
            if (buffer != null) buffer.close();
        }
    }

    private final class Page {
        final PageKey key;
        final LodChunkMesh[] chunks = new LodChunkMesh[SLOTS];
        final byte[][] geometry = new byte[SLOTS][];
        final LodChunkMesh[] installedChunks = new LodChunkMesh[SLOTS];
        final Matrix4f localMv = new Matrix4f();
        final IntBuffer counts = BufferUtils.createIntBuffer(SLOTS);
        final IntBuffer bases = BufferUtils.createIntBuffer(SLOTS);
        final PointerBuffer offsets = BufferUtils.createPointerBuffer(SLOTS);

        int[] baseVertices = new int[SLOTS];
        int[] indexCounts = new int[SLOTS];
        int maxIndexCount;
        int gpuBytes;
        long lastDrawFrame = -1;
        long revision;
        boolean queued;
        boolean building;
        boolean closed;
        PageBuild ready;
        VertexBuffer vertexBuffer;

        Page(PageKey key) {
            this.key = key;
        }

        synchronized boolean offer(LodChunkMesh chunk, byte[] data) {
            if (closed) return false;
            int slot = slot(chunk);
            if (chunks[slot] == chunk && geometry[slot] == data) return true;
            chunks[slot] = chunk;
            geometry[slot] = data;
            revision++;
            queueBuild();
            return true;
        }

        private synchronized void queueBuild() {
            if (!closed && !queued && !building) {
                queued = true;
                buildExecutor.execute(() -> buildPage(this));
            }
        }

        synchronized Snapshot snapshot() {
            queued = false;
            if (closed || building) return null;
            building = true;
            return new Snapshot(revision, chunks.clone(), geometry.clone());
        }

        synchronized void built(PageBuild build) {
            building = false;
            if (closed || revision != build.snapshot.revision) {
                build.close();
                queueBuild();
                return;
            }
            if (ready != null) ready.close();
            ready = build;
            uploadQueue.offer(this);
        }

        synchronized PageBuild takeReady() {
            PageBuild result = ready;
            ready = null;
            if (result == null) return null;
            if (closed || result.snapshot.revision != revision) {
                result.close();
                queueBuild();
                return null;
            }
            return result;
        }

        synchronized void buildFailed(long failedRevision) {
            building = false;
            if (revision != failedRevision) queueBuild();
        }

        void install(PageBuild build) {
            if (closed) return;
            VertexBuffer next = null;
            if (build.mesh != null) {
                next = new VertexBuffer(VertexBuffer.Usage.STATIC);
                try {
                    next.bind();
                    next.upload(build.mesh);
                } catch (Throwable error) {
                    next.close();
                    throw error;
                } finally {
                    VertexBuffer.unbind();
                }
            }
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = next;
            gpuBytes = build.bytes;
            baseVertices = build.baseVertices;
            indexCounts = build.indexCounts;
            maxIndexCount = build.maxIndexCount;
            for (int slot = 0; slot < SLOTS; slot++) {
                LodChunkMesh previous = installedChunks[slot];
                LodChunkMesh current = build.snapshot.chunks[slot];
                if (previous != null && previous != current) previous.onPageEvicted();
                installedChunks[slot] = current;
                if (current != null) current.onPageUploaded(build.snapshot.geometry[slot]);
            }
        }

        void draw(List<LodChunkMesh> selected, Matrix4f mv, Matrix4f proj, ShaderInstance shader, float focusX, float focusZ) {
            if (vertexBuffer == null) return;
            counts.clear();
            bases.clear();
            offsets.clear();
            for (LodChunkMesh chunk : selected) {
                byte[] geometry = chunk.geometry();
                if (geometry != null && geometry.length == 0) continue;
                int slot = slot(chunk);
                int indexCount = indexCounts[slot];
                if (indexCount == 0) continue;
                counts.put(indexCount);
                bases.put(baseVertices[slot]);
                offsets.put(0L);
            }
            if (counts.position() == 0) return;
            counts.flip();
            bases.flip();
            offsets.flip();

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
                indices.bind(maxIndexCount);
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
            for (LodChunkMesh chunk : installedChunks) {
                if (chunk != null) chunk.onPageEvicted();
            }
            Arrays.fill(installedChunks, null);
            Arrays.fill(chunks, null);
            Arrays.fill(geometry, null);
        }

        private int slot(LodChunkMesh chunk) {
            return Math.floorMod(chunk.chunkX, PAGE_SIZE) * PAGE_SIZE + Math.floorMod(chunk.chunkZ, PAGE_SIZE);
        }
    }
}
