package net.conczin.immersive_worldmap.renderer;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.screen.MapBackgroundTexture;
import net.conczin.immersive_worldmap.util.PooledBytes;
import net.conczin.immersive_worldmap.util.PriorityThreadPoolExecutor;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

public final class LodChunkPageManager {
    public static final int PAGE_SIZE = 8;
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("MapInfo", UniformType.UNIFORM_BUFFER)
            .withUniform("BackgroundTexture", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(ImmersiveWorldmap.locate("pipeline/map"))
            .withVertexShader(ImmersiveWorldmap.locate("core/immersive_worldmap_map"))
            .withFragmentShader(ImmersiveWorldmap.locate("core/immersive_worldmap_map"))
            .withBindGroupLayout(LAYOUT)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build();

    // Two matrices and three vec4s, see the map shader.
    private static final int UNIFORM_SIZE = 2 * 64 + 3 * 16;

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

    private long gpuBytes;
    private int uploadCursor;
    private volatile boolean active;
    private volatile long assemblingBytes;

    private long lastPruneNanos;
    private long pruneGraceEndNanos;

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
                     LodChunkVisibilitySelector.RenderRange range, int guiWidth, int guiHeight,
                     GpuTextureView color, GpuTextureView depth) {
        if (plan == Plan.EMPTY) return;

        long now = System.nanoTime();
        for (Page page : plan.neededPages) {
            page.lastUsedNanos = now;
        }
        uploadReady(plan);

        List<Map.Entry<Page, List<LodChunkMesh>>> batches = plan.drawPages.entrySet().stream()
                .filter(entry -> entry.getKey().vertexBuffer != null && !entry.getKey().closed)
                .toList();
        if (!batches.isEmpty()) {
            drawBatches(batches, mv, proj, focusX, focusZ, range, guiWidth, guiHeight, color, depth);
        }
        pruneUnusedPages(now);
    }

    private void drawBatches(List<Map.Entry<Page, List<LodChunkMesh>>> batches, Matrix4f mv, Matrix4f proj,
                             float focusX, float focusZ, LodChunkVisibilitySelector.RenderRange range,
                             int guiWidth, int guiHeight, GpuTextureView color, GpuTextureView depth) {
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        int stride = (UNIFORM_SIZE + alignment - 1) / alignment * alignment;

        int side = (int) Math.ceil(Math.hypot(guiWidth, guiHeight));
        float uvX = (float) guiWidth / side;
        float uvY = (float) guiHeight / side;
        float uvOffsetX = -(float) ((guiWidth - side) / 2) / side;
        float uvOffsetY = -(float) ((guiHeight - side) / 2) / side;

        ByteBuffer uniforms = MemoryUtil.memCalloc(stride * batches.size());
        GpuBuffer uniformBuffer;
        try {
            for (int i = 0; i < batches.size(); i++) {
                Page page = batches.get(i).getKey();
                float scale = 1 << page.key.lod;
                float originX = page.key.x * PAGE_SIZE * 16f * scale;
                float originZ = page.key.z * PAGE_SIZE * 16f * scale;
                localMv.set(mv).translate(originX, 0, originZ).scale(scale, scale, scale);
                Std140Builder.intoBuffer(uniforms.slice(i * stride, UNIFORM_SIZE))
                        .putMat4f(localMv)
                        .putMat4f(proj)
                        .putVec4(uvX, uvY, uvOffsetX, uvOffsetY)
                        .putVec4(originX - focusX, originZ - focusZ, scale, 0f)
                        .putVec4(range.start(), range.end(), color.getWidth(0), color.getHeight(0));
            }
            uniformBuffer = device.createBuffer(() -> "Immersive Worldmap uniforms", GpuBuffer.USAGE_UNIFORM, uniforms);
        } finally {
            MemoryUtil.memFree(uniforms);
        }

        try (uniformBuffer) {
            DynamicTexture background = MapBackgroundTexture.texture();
            int maxIndexCount = batches.stream().mapToInt(entry -> entry.getKey().uploaded.drawIndexCount).max().orElse(0);
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(maxIndexCount);

            try (RenderPass pass = device.createCommandEncoder().createRenderPass(
                    () -> "Immersive Worldmap", color, Optional.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                pass.setUniform("BackgroundTexture", background.getTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setIndexBuffer(indexBuffer, indices.type());
                for (int i = 0; i < batches.size(); i++) {
                    Map.Entry<Page, List<LodChunkMesh>> batch = batches.get(i);
                    pass.setUniform("MapInfo", uniformBuffer.slice((long) i * stride, UNIFORM_SIZE));
                    batch.getKey().draw(pass, batch.getValue());
                }
            }
        }
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
        pruneGraceEndNanos = 0;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
        if (!active) return;

        // Retained pages were last used before the close; refresh them and hold off
        // pruning until the opening animation settles and the selector has run again.
        long now = System.nanoTime();
        for (Page page : pages.values()) {
            page.lastUsedNanos = now;
        }
        lastPruneNanos = now;
        pruneGraceEndNanos = now + 1_500_000_000L;
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
        PageData data = new PageData(snapshot, baseVertices, indexCounts, totalBytes, maxIndexCount);
        if (totalBytes == 0) return new PageBuild(data, null);
        assemblingBytes = totalBytes;
        ByteBuffer buffer = MemoryUtil.memAlloc(totalBytes);
        try {
            ByteBuffer target = buffer.duplicate();
            for (PooledBytes geometry : snapshot.geometry) {
                if (geometry != null) geometry.copyTo(target);
            }
            return new PageBuild(data, buffer);
        } catch (Throwable error) {
            MemoryUtil.memFree(buffer);
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
        if (now < pruneGraceEndNanos || now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
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
            int drawIndexCount
    ) {
    }

    private record PageBuild(PageData data, ByteBuffer buffer) implements AutoCloseable {

        @Override
        public void close() {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
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
        GpuBuffer vertexBuffer;

        Page(PageKey key) {
            this.key = key;
        }

        long readyBytes() {
            PageBuild build = ready;
            return build == null ? 0 : build.data.bytes;
        }

        synchronized void update(List<LodChunkMesh> meshes) {
            if (closed || building || ready != null) return;

            // Keep uploaded slots resident when the camera temporarily excludes them.
            LodChunkMesh[] chunks = uploaded == null ? new LodChunkMesh[SLOTS] : uploaded.snapshot.chunks.clone();
            for (LodChunkMesh mesh : meshes) {
                chunks[slot(mesh)] = mesh;
            }

            PooledBytes[] geometry = new PooledBytes[SLOTS];
            long revision = 0;
            boolean hasGeometry = false;
            for (int slot = 0; slot < SLOTS; slot++) {
                LodChunkMesh mesh = chunks[slot];
                if (mesh == null) continue;

                long meshRevision = mesh.copyGeometry(geometry, slot);
                if (geometry[slot] == null && uploaded != null && uploaded.snapshot.chunks[slot] == mesh) {
                    geometry[slot] = uploaded.snapshot.geometry[slot];
                }
                if (geometry[slot] == null) {
                    chunks[slot] = null;
                    continue;
                }
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
                    GpuBuffer previous = vertexBuffer;
                    vertexBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "Immersive Worldmap page", GpuBuffer.USAGE_VERTEX, build.buffer);
                    if (previous != null) previous.close();
                } else if (vertexBuffer != null) {
                    vertexBuffer.close();
                    vertexBuffer = null;
                }

                gpuBytes += build.data.bytes - (uploaded == null ? 0 : uploaded.bytes);
                uploaded = build.data;
            }
            return true;
        }

        void draw(RenderPass pass, List<LodChunkMesh> selected) {
            pass.setVertexBuffer(0, vertexBuffer.slice());
            for (LodChunkMesh chunk : selected) {
                int slot = slot(chunk);
                if (uploaded.snapshot.chunks[slot] != chunk) continue;
                PooledBytes geometry = chunk.geometry();
                if (geometry != null && geometry.length() == 0) continue;
                int indexCount = uploaded.indexCounts[slot];
                if (indexCount == 0) continue;
                // Quad indices start at zero for every mesh and only the base vertex varies.
                pass.drawIndexed(indexCount, 1, 0, uploaded.baseVertices[slot], 0);
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
