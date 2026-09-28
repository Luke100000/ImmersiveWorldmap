package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
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
    private volatile boolean loaded;
    private volatile int faces;
    private final AtomicLong revision = new AtomicLong();
    private final Matrix4f localMv = new Matrix4f();
    private VertexBuffer vertexBuffer;

    public LodChunkMesh(int chunkX, int chunkZ, int lod, String dimension) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.lod = lod;
        this.dimension = dimension;
    }

    public boolean isLoaded() {
        return loaded;
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
                loaded = true;
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

    // Must be called from the render thread. Returns the faces submitted, for the viewer's counter.
    public int draw(Matrix4f mv, Matrix4f proj) {
        if (pendingMeshReady) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            mesh = pendingMesh;
            pendingMesh = null;
            pendingMeshReady = false;
        }
        if (mesh == null) return 0;
        if (vertexBuffer == null) {
            MeshData.DrawState drawState = mesh.drawState();
            vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertexBuffer.bind();
            // upload() closes the mesh data
            vertexBuffer.upload(mesh);
            VertexBuffer.unbind();
            faces = drawState.vertexCount() / 4;
        }
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return 0;

        float scale = 1 << lod;
        localMv.set(mv)
                .translate(chunkX * 16f * scale, 0, chunkZ * 16f * scale)
                .scale(scale, scale, scale);

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(localMv, proj, shader);
        VertexBuffer.unbind();
        return faces;
    }

    public void close() {
        mesh = null;
        pendingMesh = null;
        pendingMeshReady = false;
        dirty = true;
        requested = false;
        loaded = false;
        faces = 0;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
