package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMesh;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.settings.SharedSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LodViewerScreen extends Screen {
    private static final int KEY_ESCAPE = 256;
    private static final int KEY_TAB = 258;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;
    private static final int CAVE_OFFSET_STEP = 8;

    private final Minecraft minecraft;
    private String dimension;
    private final Camera3D camera = new Camera3D();

    private int caveBaselineY = 0;
    private final Set<Integer> heldKeys = new HashSet<>();

    public LodViewerScreen() {
        super(Component.literal("LOD Chunk Viewer"));

        this.minecraft = Minecraft.getInstance();

        boolean staleCaveMeshes = SharedSettings.caveView;
        loadState();
        resetCaveView();
        if (staleCaveMeshes) {
            clearMeshes();
        }
    }

    private void loadState() {
        if (minecraft.player == null || minecraft.level == null) return;

        dimension = minecraft.level.dimension().location().toString();
        camera.setTarget((float) minecraft.player.getX(),
                (float) (minecraft.player.getY() - minecraft.level.getMinBuildHeight()),
                (float) minecraft.player.getZ());
        camera.setZoom(500f);

        caveBaselineY = (int) Math.floor(minecraft.player.getY()) - minecraft.level.getMinBuildHeight();
    }

    private void resetCaveView() {
        SharedSettings.caveView = false;
        SharedSettings.caveViewBaselineY = caveBaselineY;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Clear LODs"), button -> clearGeneratedLods())
                .bounds(this.width - 250, 20, 110, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Clear meshes"), button -> clearMeshes())
                .bounds(this.width - 130, 20, 110, 20)
                .build());
    }

    private void clearGeneratedLods() {
        if (dimension == null || !DatabaseManager.isInitialized()) {
            return;
        }
        ChunkLodProcessor.clearGeneratedLods(dimension);
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();
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
        if (isCaveKey(keyCode)) {
            if (heldKeys.add(keyCode)) {
                switch (keyCode) {
                    case KEY_TAB -> SharedSettings.caveView = !SharedSettings.caveView;
                    case KEY_UP -> SharedSettings.caveViewBaselineY += CAVE_OFFSET_STEP;
                    case KEY_DOWN -> SharedSettings.caveViewBaselineY -= CAVE_OFFSET_STEP;
                }
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

    private static boolean isCaveKey(int keyCode) {
        return keyCode == KEY_TAB || keyCode == KEY_UP || keyCode == KEY_DOWN;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // TODO: Gradient
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

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.drawString(this.font, "Dimension: " + dimension, 20, 40, 0xFFFFFF);

        int caveOffset = SharedSettings.caveViewBaselineY - caveBaselineY;
        graphics.drawString(this.font,
                "Cave view: " + (SharedSettings.caveView ? "ON" : "OFF")
                + "  slice Y=" + SharedSettings.caveViewBaselineY
                + "  offset=" + (caveOffset >= 0 ? "+" : "") + caveOffset,
                20, 52, SharedSettings.caveView ? 0xFFAA55 : 0xAAAAAA);

        graphics.drawString(this.font, "LMB: orbit   RMB: pan   Wheel: zoom   WASD: pan   Tab: cave view   Up/Down: slice",
                20, this.height - 20, 0x888888);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        LodChunkPageManager.INSTANCE.draw(visible, mv, proj);

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
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
