package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.settings.SharedSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

public class LodViewerScreen extends Screen {
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_TAB = 258;
    private static final int PLAYER_MARKER_SIZE = 16;
    private static final float OPENING_DURATION_SECONDS = 0.6f;

    private final Minecraft minecraft;
    private String dimension;
    private final Camera3D camera = new Camera3D();
    private long openingStartNanos;

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
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        dimension = minecraft.level.dimension().location().toString();
        camera.setTarget((float) minecraft.player.getX(),
                (float) (minecraft.player.getY() - minecraft.level.getMinBuildHeight()),
                (float) minecraft.player.getZ());
        SharedSettings.caveView = minecraft.level.getBrightness(LightLayer.SKY, minecraft.player.blockPosition()) == 0;
        SharedSettings.caveViewBaselineY = (int) Math.floor(minecraft.player.getY()) - minecraft.level.getMinBuildHeight();
        camera.setZoom(SharedSettings.caveView ? 150f : 225f);
        camera.setRotation(135f, SharedSettings.caveView ? -60 : -45f);
    }

    private void clearMeshes() {
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();
    }

    private void clearDimensionLods() {
        DatabaseManager.getInstance().clearDimension(dimension);
        ChunkLodProcessor.clearCache();
        clearMeshes();
    }

    private void clearGeneratedLods() {
        DatabaseManager.getInstance().clearGeneratedLods(dimension);
        ChunkLodProcessor.clearCache();
        clearMeshes();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return camera.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (camera.mouseReleased(button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (camera.mouseDragged(mouseX, mouseY, button, width, height)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (camera.mouseScrolled(scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ImmersiveWorldmap.MAP_VIEWER_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        // Debug-only destructive actions, gated behind the F3 overlay.
        if (minecraft.getDebugOverlay().showDebugScreen()) {
            if (keyCode == GLFW.GLFW_KEY_F6) {
                clearDimensionLods();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F7) {
                clearGeneratedLods();
                return true;
            }
        }

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

        long now = System.nanoTime();
        if (openingStartNanos == 0) {
            openingStartNanos = now;
        }
        float openingProgress = Math.min((now - openingStartNanos) / (OPENING_DURATION_SECONDS * 1_000_000_000f), 1f);
        float openingBonus = 1f - openingProgress * openingProgress * (3f - 2f * openingProgress);

        camera.tick();
        LodChunkMeshManager.tick();

        float zoom = camera.getSmoothZoom() * (1f + 0.3f * openingBonus);
        float yawDegrees = camera.getSmoothYaw() + 10f * openingBonus;
        Matrix4f mv, proj;
        {
            float pitchDegrees = camera.getSmoothPitch() - 10f * openingBonus;
            float yaw = (float) Math.toRadians(yawDegrees);
            float pitch = (float) Math.toRadians(pitchDegrees);
            float targetX = camera.getSmoothTargetX();
            float targetY = camera.getSmoothTargetY();
            float targetZ = camera.getSmoothTargetZ();

            float eyeX = targetX - (float) (Math.sin(yaw) * Math.cos(pitch)) * zoom;
            float eyeY = targetY - (float) (Math.sin(pitch)) * zoom;
            float eyeZ = targetZ - (float) (Math.cos(yaw) * Math.cos(pitch)) * zoom;

            mv = new Matrix4f().lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0, 1, 0);
            proj = new Matrix4f().setPerspective((float) Math.toRadians(60.0), (float) width / height, zoom * 0.1f, zoom * 10f);

            LodChunkVisibilitySelector.get().update(dimension, targetX, targetY, targetZ,
                    yawDegrees, pitchDegrees, zoom, (float) width / height);
        }

        graphics.flush();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        LodChunkPageManager.INSTANCE.draw(LodChunkVisibilitySelector.get().selection(), mv, proj,
                camera.getSmoothTargetX(), camera.getSmoothTargetZ(),
                LodChunkVisibilitySelector.renderRadius(zoom));

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        renderPlayers(graphics, mv, proj);

        if (minecraft.getDebugOverlay().showDebugScreen()) {
            int tasks = ChunkLodProcessor.getPendingTaskCount() + LodChunkPageManager.INSTANCE.getPendingTaskCount();
            drawOverlay(graphics, "Tasks: " + tasks, 20, 20);
            drawOverlay(graphics, "F6: clear dimension LODs (database + cache)", 20, 40);
            drawOverlay(graphics, "F7: clear generated LODs (LOD > 0)", 20, 60);
        }

        String closeKey = ImmersiveWorldmap.MAP_VIEWER_KEY.getTranslatedKeyMessage().getString();
        String controls = "LMB: pan   RMB: orbit   Wheel: zoom   WASD/Arrows: pan   Q/E: rotate   Tab: cave view   " + closeKey + ": close";
        drawOverlay(graphics, controls, 20, this.height - 20);

        MapCompass.render(graphics, font, yawDegrees, width, height);

        int fadeAlpha = Math.round(openingBonus * 255f);
        if (fadeAlpha > 0) {
            graphics.fill(0, 0, this.width, this.height, fadeAlpha << 24);
        }
    }

    private void drawOverlay(GuiGraphics graphics, String text, int x, int y) {
        int padding = 4;
        graphics.fill(x - padding, y - padding,
                x + this.font.width(text) + padding, y + this.font.lineHeight + padding,
                0x80000000);
        graphics.drawString(this.font, text, x, y, 0xFFFFFFFF);
    }

    private void renderPlayers(GuiGraphics graphics, Matrix4f mv, Matrix4f proj) {
        if (minecraft.level == null || !minecraft.level.dimension().location().toString().equals(dimension)) {
            return;
        }

        Matrix4f viewProjection = new Matrix4f(proj).mul(mv);
        Vector4f position = new Vector4f();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(!minecraft.level.tickRateManager().isEntityFrozen(player));
            Vec3 worldPosition = player.getPosition(partialTick);
            position.set((float) worldPosition.x,
                    (float) (worldPosition.y - minecraft.level.getMinBuildHeight()),
                    (float) worldPosition.z, 1f);
            viewProjection.transform(position);

            // Clip before dividing so players behind the camera cannot appear on the map.
            if (position.w <= 0f || Math.abs(position.x) > position.w
                || Math.abs(position.y) > position.w || Math.abs(position.z) > position.w) {
                continue;
            }

            float x = (position.x / position.w + 1f) * width * 0.5f - PLAYER_MARKER_SIZE / 2f;
            float y = (1f - position.y / position.w) * height * 0.5f - PLAYER_MARKER_SIZE / 2f;
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(x, y, 0f);
                graphics.fill(-1, -1, PLAYER_MARKER_SIZE + 1, PLAYER_MARKER_SIZE + 1, 0xFF000000);
                graphics.flush();
                RenderSystem.disableDepthTest();
                PlayerFaceRenderer.draw(graphics, player.getSkin().texture(), 0, 0, PLAYER_MARKER_SIZE,
                        player.isModelPartShown(PlayerModelPart.HAT), LivingEntityRenderer.isEntityUpsideDown(player));
            } finally {
                graphics.pose().popPose();
            }
        }
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
