package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.lod.LodChunkRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class LodViewerScreen extends Screen {
    private final Minecraft minecraft;
    private VertexBuffer vertexBuffer;
    private int chunkX;
    private int chunkZ;
    private String dimension;
    private boolean hasData = false;
    private int chunkHeight = 384; // Default, updated when data loads

    public LodViewerScreen() {
        super(Component.literal("LOD Chunk Viewer"));
        this.minecraft = Minecraft.getInstance();
        loadCurrentChunk();
    }

    private void loadCurrentChunk() {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        BlockPos playerPos = minecraft.player.blockPosition();
        ChunkPos chunkPos = new ChunkPos(playerPos);

        this.chunkX = chunkPos.x;
        this.chunkZ = chunkPos.z;
        this.dimension = minecraft.level.dimension().location().toString();

        // Load LOD data
        LodChunkData lodData = ChunkLodProcessor.getLodChunkData(chunkX, chunkZ, dimension, 0);

        if (lodData != null) {
            chunkHeight = lodData.getHeight();
            buildMesh(lodData);
            hasData = true;
        } else {
            hasData = false;
        }
    }

    private void buildMesh(LodChunkData lodData) {
        // Clean up old buffer
        if (vertexBuffer != null) {
            vertexBuffer.close();
        }

        // Build new mesh
        Tesselator tesselator = Tesselator.getInstance();
        MeshData meshData = LodChunkRenderer.buildMesh(lodData, tesselator);

        // Upload to GPU
        vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vertexBuffer.bind();
        vertexBuffer.upload(meshData);
        VertexBuffer.unbind();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw background
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Draw chunk info
        graphics.drawString(this.font, "Chunk: " + chunkX + ", " + chunkZ, 20, 40, 0xFFFFFF);
        graphics.drawString(this.font, "Dimension: " + dimension, 20, 52, 0xFFFFFF);

        if (!hasData) {
            graphics.drawCenteredString(this.font, "No LOD data available", this.width / 2, this.height / 2, 0xFF5555);
            return;
        }

        // Render the LOD mesh
        renderLodMesh(graphics);
    }

    private void renderLodMesh(GuiGraphics graphics) {
        if (vertexBuffer == null) {
            return;
        }

        graphics.pose().pushPose();

        // Center the view
        graphics.pose().translate(this.width / 2.0F, this.height / 2.0F, 400.0F);

        // Apply rotation for a better view
        float rotation = (System.currentTimeMillis() % 10000) / 10000.0F * 360.0F;
        graphics.pose().mulPose(new Quaternionf().rotateXYZ((float) Math.toRadians(-30), 0, 0));
        graphics.pose().mulPose(new Quaternionf().rotateXYZ(0, (float) Math.toRadians(rotation), 0));

        // Scale down the chunk (full vertical chunk needs less scale)
        graphics.pose().scale(1.5F, -1.5F, 1.5F);

        // Center the chunk at origin (y center at height/2)
        graphics.pose().translate(-8.0F, -chunkHeight / 2.0F, -8.0F);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorTexLightmapShader);

        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0f, this.width, this.height, 0f, -1000f, 1000f),
                VertexSorting.DISTANCE_TO_ORIGIN
        );

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(graphics.pose().last().pose(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();

        RenderSystem.disableCull();

        graphics.pose().popPose();
    }


    @Override
    public void onClose() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

