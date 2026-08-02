package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

public class LodChunkMesh {
    public final int chunkX;
    public final int chunkZ;
    public final int lod;
    public final String dimension;

    private volatile MeshData mesh;
    private volatile MeshData pendingMesh;
    private volatile boolean pendingMeshReady;
    private volatile boolean dirty = true;
    private volatile boolean requested;
    private final AtomicLong revision = new AtomicLong();
    private VertexBuffer vertexBuffer;

    public LodChunkMesh(int chunkX, int chunkZ, int lod, String dimension) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.lod = lod;
        this.dimension = dimension;
    }

    public boolean isLoaded() {
        return mesh != null;
    }

    public void requestLoad() {
        if (requested || !dirty) return;
        requested = true;
        long requestedRevision = revision.get();
        LodChunkMeshBuilder.buildMeshAsync(chunkX, chunkZ, dimension, lod).whenComplete((result, error) -> {
            if (error == null && revision.get() == requestedRevision) {
                pendingMesh = result;
                pendingMeshReady = true;
                dirty = false;
            } else if (error != null && !isCancellation(error)) {
                ImmersiveWorldmap.LOGGER.error("Failed to load chunk LOD data: {}", error.getMessage());
            }
            requested = false;
        });
    }

    public void markDirty() {
        revision.incrementAndGet();
        dirty = true;
    }

    private static boolean isCancellation(Throwable error) {
        while (error != null) {
            if (error instanceof CancellationException) return true;
            error = error.getCause();
        }
        return false;
    }

    // Must be called from the render thread.
    public void draw(Matrix4f mv, Matrix4f proj) {
        if (pendingMeshReady) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            mesh = pendingMesh;
            pendingMesh = null;
            pendingMeshReady = false;
        }
        if (mesh == null) return;
        if (vertexBuffer == null) {
            vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertexBuffer.bind();
            vertexBuffer.upload(mesh);
            VertexBuffer.unbind();
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var shader = RenderSystem.getShader();
        if (shader == null) return;

        float scale = 1 << lod;
        float worldX = chunkX * 16f * scale;
        float worldZ = chunkZ * 16f * scale;
        Matrix4f localMv = new Matrix4f(mv)
                .translate(worldX, 0, worldZ)
                .scale(scale, scale, scale);

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(localMv, proj, shader);
        VertexBuffer.unbind();
    }

    public void close() {
        mesh = null;
        pendingMesh = null;
        pendingMeshReady = false;
        dirty = true;
        requested = false;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
