package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMesh;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.settings.SharedSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LodViewerScreen extends Screen {
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_TAB = 258;

    private final Minecraft minecraft;
    private String dimension;
    private final Camera3D camera = new Camera3D();

    private final Set<Integer> heldKeys = new HashSet<>();

    public LodViewerScreen() {
        super(Component.literal("LOD Chunk Viewer"));

        this.minecraft = Minecraft.getInstance();

        boolean previousCaveView = SharedSettings.caveView;
        int previousBaselineY = SharedSettings.caveViewBaselineY;
        loadState();
        if (previousCaveView != SharedSettings.caveView
            || (SharedSettings.caveView && previousBaselineY != SharedSettings.caveViewBaselineY)) {
            clearMeshes();
        }
    }

    private void loadState() {
        if (minecraft.player == null || minecraft.level == null) return;

        dimension = minecraft.level.dimension().location().toString();
        camera.setTarget((float) minecraft.player.getX(),
                (float) (minecraft.player.getY() - minecraft.level.getMinBuildHeight()),
                (float) minecraft.player.getZ());
        SharedSettings.caveView = minecraft.level.getBrightness(LightLayer.SKY, minecraft.player.blockPosition()) == 0;
        SharedSettings.caveViewBaselineY = (int) Math.floor(minecraft.player.getY()) - minecraft.level.getMinBuildHeight();
        camera.setZoom(SharedSettings.caveView ? 200f : 500f);
    }

    private void clearMeshes() {
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();
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
        if (keyCode == KEY_TAB) {
            if (heldKeys.add(keyCode)) {
                SharedSettings.caveView = !SharedSettings.caveView;
                clearMeshes();
            }
            return true;
        }

        if (keyCode != KEY_ESCAPE && camera.keyPressed(keyCode)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        heldKeys.remove(keyCode);

        if (camera.keyReleased(keyCode)) {
            return true;
        }

        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int side = (int) Math.ceil(Math.hypot(width, height));
        RenderSystem.depthMask(false);
        try {
            g.blit(MapBackgroundTexture.get(), (width - side) / 2, (height - side) / 2,
                    side, side, 0f, 0f, MapBackgroundTexture.SIZE, MapBackgroundTexture.SIZE, MapBackgroundTexture.SIZE, MapBackgroundTexture.SIZE);
        } finally {
            RenderSystem.depthMask(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        camera.tick();
        LodChunkMeshManager.tick();

        Matrix4f mv, proj;
        {
            float yaw = (float) Math.toRadians(camera.getSmoothYaw());
            float pitch = (float) Math.toRadians(camera.getSmoothPitch());
            float zoom = camera.getSmoothZoom();
            float targetX = camera.getSmoothTargetX();
            float targetY = camera.getSmoothTargetY();
            float targetZ = camera.getSmoothTargetZ();

            float eyeX = targetX - (float) (Math.sin(yaw) * Math.cos(pitch)) * zoom;
            float eyeY = targetY - (float) (Math.sin(pitch)) * zoom;
            float eyeZ = targetZ - (float) (Math.cos(yaw) * Math.cos(pitch)) * zoom;

            mv = new Matrix4f().lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0, 1, 0);
            proj = new Matrix4f().setPerspective((float) Math.toRadians(60.0), (float) width / height, zoom * 0.1f, zoom * 10f);

            LodChunkVisibilitySelector.get().update(mv, proj, dimension, targetX, targetZ, zoom);
        }

        List<LodChunkMesh> visible = LodChunkVisibilitySelector.get().visibleChunks();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        LodChunkPageManager.INSTANCE.draw(visible, mv, proj);

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        String controls = "LMB: orbit   RMB: pan   Wheel: zoom   WASD/Arrows: pan   Tab: cave view";
        int padding = 4;
        int controlsX = 20;
        int controlsY = this.height - 20;
        graphics.fill(controlsX - padding, controlsY - padding,
                controlsX + this.font.width(controls) + padding, controlsY + this.font.lineHeight + padding,
                0x80000000);
        graphics.drawString(this.font, controls, controlsX, controlsY, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        ChunkLodProcessor.clearQueuedViewportTasks();
        LodChunkVisibilitySelector.reset();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
