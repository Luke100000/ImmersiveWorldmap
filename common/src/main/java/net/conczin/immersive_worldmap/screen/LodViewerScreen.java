package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import java.util.concurrent.CompletableFuture;

public class LodViewerScreen extends Screen {
    private final Minecraft minecraft;
    private VertexBuffer vertexBuffer;
    private CompletableFuture<MeshData> meshFuture;
    private int chunkX;
    private int chunkZ;
    private String dimension;
    private boolean hasData = false;
    private boolean isLoading = false;
    private int chunkHeight = 384;

    private final Camera3D camera = new Camera3D();

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

        int lod = 2;
        this.chunkX = chunkPos.x >> lod;
        this.chunkZ = chunkPos.z >> lod;
        this.dimension = minecraft.level.dimension().location().toString();
        chunkHeight = 384 >> lod;

        // centre pan on the chunk, zoom to fit it
        camera.setPan(chunkX * 16f + 8f, chunkZ * 16f + 8f);
        camera.setZoom(Math.max(chunkHeight, 32) * 2f);

        isLoading = true;
        hasData = false;

        meshFuture = LodChunkRenderer.buildMesh(chunkX, chunkZ, dimension, lod);
        meshFuture.thenAccept(meshData -> {
            if (meshData != null) {
                isLoading = false;
                hasData = true;
            } else {
                isLoading = false;
                hasData = false;
            }
        }).exceptionally(throwable -> {
            isLoading = false;
            hasData = false;
            throwable.printStackTrace();
            return null;
        });
    }

    private void uploadMeshIfReady() {
        if (meshFuture != null && meshFuture.isDone() && !meshFuture.isCompletedExceptionally()) {
            try {
                MeshData meshData = meshFuture.getNow(null);
                if (meshData != null) {
                    if (vertexBuffer != null) {
                        vertexBuffer.close();
                    }
                    vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vertexBuffer.bind();
                    vertexBuffer.upload(meshData);
                    VertexBuffer.unbind();
                }
                meshFuture = null;
            } catch (Exception e) {
                meshFuture = null;
                hasData = false;
            }
        }
    }


    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (camera.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (camera.mouseReleased(button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (camera.mouseDragged(mouseX, mouseY, button, width, height)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (camera.mouseScrolled(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode != 256 && camera.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (camera.keyReleased(keyCode)) return true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    public void renderBackground(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        //this.renderPanorama(pGuiGraphics, pPartialTick);
        //this.renderBlurredBackground(pPartialTick);
        //this.renderMenuBackground(pGuiGraphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        camera.tick();

        uploadMeshIfReady();

        // Draw background
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Draw chunk info
        graphics.drawString(this.font, "Chunk: " + chunkX + ", " + chunkZ, 20, 40, 0xFFFFFF);
        graphics.drawString(this.font, "Dimension: " + dimension, 20, 52, 0xFFFFFF);
        graphics.drawString(this.font, "Tasks: " + ChunkLodProcessor.EXECUTOR.getQueue().size(), 20, 64, 0xFFFFFF);
        graphics.drawString(this.font,
                String.format("Zoom: %.1f  Yaw: %.1f  Pitch: %.1f",
                        camera.getSmoothZoom(), camera.getSmoothYaw(), camera.getSmoothPitch()),
                20, 76, 0xAAAAAA);
        graphics.drawString(this.font, "LMB: rotate   RMB: pan   Wheel: zoom   WASD: pan", 20, this.height - 20, 0x888888);

        if (isLoading) {
            graphics.drawCenteredString(this.font, "Loading...", this.width / 2, this.height / 2, 0xFFFF55);
            return;
        }

        if (!hasData) {
            graphics.drawCenteredString(this.font, "No LOD data available", this.width / 2, this.height / 2, 0xFF5555);
            return;
        }

        // Render the LOD mesh
        renderLodMesh();
    }

    private void renderLodMesh() {
        if (vertexBuffer == null) return;

        float yaw = (float) Math.toRadians(camera.getSmoothYaw());
        float pitch = (float) Math.toRadians(camera.getSmoothPitch());
        float zoom = camera.getSmoothZoom();
        float camX = camera.getSmoothX();
        float camZ = camera.getSmoothZ();

        // Eye position: orbit point + zoom units along the view direction (inverted)
        float eyeX = camX - (float) (Math.sin(yaw) * Math.cos(pitch)) * zoom;
        float eyeY = -(float) (Math.sin(pitch)) * zoom;
        float eyeZ = camZ - (float) (Math.cos(yaw) * Math.cos(pitch)) * zoom;

        Matrix4f view = new Matrix4f().lookAt(
                eyeX, eyeY, eyeZ,
                camX, 0, camZ,
                0, 1, 0
        );

        // Model: translate so chunk centre is at world origin
        Matrix4f model = new Matrix4f().translate(
                -(chunkX * 16f) - 8f,
                -chunkHeight * 0.5f,
                -(chunkZ * 16f) - 8f
        );

        Matrix4f proj = new Matrix4f().setPerspective(
                (float) Math.toRadians(60.0),
                (float) width / height,
                zoom * 0.01f, zoom * 10f
        );

        Matrix4f mv = new Matrix4f(view).mul(model);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(mv, proj, RenderSystem.getShader());
        VertexBuffer.unbind();

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
    }


    @Override
    public void onClose() {
        if (meshFuture != null && !meshFuture.isDone()) {
            meshFuture.cancel(true);
        }

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
