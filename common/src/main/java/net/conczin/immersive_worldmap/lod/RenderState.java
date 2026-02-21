package net.conczin.immersive_worldmap.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.concurrent.CompletableFuture;

public class RenderState implements AutoCloseable {
    public final int chunkX;
    public final int chunkZ;
    public final int lod;
    public final String dimension;

    private volatile MeshData mesh;
    private VertexBuffer vertexBuffer;

    public RenderState(int chunkX, int chunkZ, int lod, String dimension) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.lod = lod;
        this.dimension = dimension;
    }

    public boolean isLoaded() {
        return mesh != null;
    }

    public void requestLoad() {
        CompletableFuture.supplyAsync(
                () -> LodChunkRenderer.buildMeshSync(chunkX, chunkZ, dimension, lod),
                ChunkLodProcessor.EXECUTOR
        ).thenAccept(result -> mesh = result);
    }

    // Must be called from the render thread.
    public void draw(Matrix4f mv, Matrix4f proj) {
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
        vertexBuffer.bind();
        vertexBuffer.drawWithShader(mv, proj, shader);
        VertexBuffer.unbind();
    }

    @Override
    public void close() {
        mesh = null;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
