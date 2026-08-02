package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.renderer.LodChunkMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import java.util.List;

public class LodViewerScreen extends Screen {
    private final Minecraft minecraft;
    private String dimension;

    private final Camera3D camera = new Camera3D();

    public LodViewerScreen() {
        super(Component.literal("LOD Chunk Viewer"));
        this.minecraft = Minecraft.getInstance();
        loadState();
    }

    private void loadState() {
        if (minecraft.player == null || minecraft.level == null) return;

        BlockPos playerPos = minecraft.player.blockPosition();
        ChunkPos chunkPos = new ChunkPos(playerPos);
        dimension = minecraft.level.dimension().location().toString();

        camera.setPan(chunkPos.x * 16f + 8f, chunkPos.z * 16f + 8f);
        camera.setZoom(512f);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Clear database"), button -> clearDimension())
                .bounds(this.width - 250, 20, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Clear meshes"), button -> clearMeshes())
                .bounds(this.width - 130, 20, 110, 20)
                .build());
    }

    private void clearDimension() {
        if (dimension == null || !DatabaseManager.isInitialized()) {
            return;
        }
        ChunkLodProcessor.clearDimension(dimension);
        LodChunkMeshManager.INSTANCE.clear();
        LodChunkVisibilitySelector.reset();
    }

    private void clearMeshes() {
        LodChunkMeshManager.INSTANCE.clear();
        LodChunkVisibilitySelector.reset();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return camera.mouseClicked(mouseX, mouseY, button);
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

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // TODO: Gradient
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        camera.tick();

        Matrix4f mv, proj;
        {
            float yaw = (float) Math.toRadians(camera.getSmoothYaw());
            float pitch = (float) Math.toRadians(camera.getSmoothPitch());
            float zoom = camera.getSmoothZoom();
            float camX = camera.getSmoothX();
            float camZ = camera.getSmoothZ();

            float eyeX = camX - (float) (Math.sin(yaw) * Math.cos(pitch)) * zoom;
            float eyeY = -(float) (Math.sin(pitch)) * zoom;
            float eyeZ = camZ - (float) (Math.cos(yaw) * Math.cos(pitch)) * zoom;

            mv = new Matrix4f().lookAt(eyeX, eyeY, eyeZ, camX, 0, camZ, 0, 1, 0);
            proj = new Matrix4f().setPerspective((float) Math.toRadians(60.0), (float) width / height, zoom * 0.1f, zoom * 10f);
        }

        LodChunkVisibilitySelector.get().update(mv, proj, dimension);

        List<LodChunkMesh> visible = LodChunkVisibilitySelector.get().visibleChunks();

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.drawString(this.font, "Dimension: " + dimension, 20, 40, 0xFFFFFF);
        graphics.drawString(this.font, "Tasks: " + ChunkLodProcessor.EXECUTOR.getProcessedTasks()
                + " / " + ChunkLodProcessor.EXECUTOR.getTotalTasks(), 20, 52, 0xFFFFFF);
        graphics.drawString(this.font, "Visible chunks: " + visible.size(), 20, 64, 0xFFFFFF);
        graphics.drawString(this.font,
                String.format("Zoom: %.1f  Yaw: %.1f  Pitch: %.1f",
                        camera.getSmoothZoom(), camera.getSmoothYaw(), camera.getSmoothPitch()),
                20, 76, 0xAAAAAA);
        graphics.drawString(this.font, "LMB: rotate   RMB: pan   Wheel: zoom   WASD: pan", 20, this.height - 20, 0x888888);

        if (visible.isEmpty()) {
            graphics.drawCenteredString(this.font, "Loading...", this.width / 2, this.height / 2, 0xFFFF55);
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        for (LodChunkMesh rs : visible) {
            rs.draw(mv, proj);
        }

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
