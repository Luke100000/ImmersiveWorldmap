package net.conczin.immersive_worldmap.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.concurrent.CompletableFuture;

public class RenderState {
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
        ).thenAccept(result -> mesh = result).exceptionally(ex -> {
            ex.printStackTrace();
            return null;
        });
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
        // TODO
        mesh = null;
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }
}
