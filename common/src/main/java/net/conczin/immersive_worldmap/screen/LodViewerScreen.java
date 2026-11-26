package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.ImmersiveWorldmapClient;
import net.conczin.immersive_worldmap.debug.MapDebug;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.renderer.MapMemoryTracker;
import net.conczin.immersive_worldmap.config.Config;
import net.conczin.immersive_worldmap.settings.SharedSettings;
import net.conczin.immersive_worldmap.util.TickLruCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LodViewerScreen extends Screen {
    private static final ResourceLocation PLAYER_FACING_ARROW = ImmersiveWorldmap.locate("textures/gui/player_facing_arrow.png");
    private static final int PLAYER_MARKER_SIZE = 16;
    private static final int OVERLAY_PADDING = 4;
    private static final int MAX_CAVE_AIR_BLOCKS = 64;
    private static final float DEFAULT_YAW = 135f;
    private static final float OPENING_DURATION_SECONDS = 0.6f;
    private static boolean playerFacingYaw;

    private final Minecraft minecraft;
    private String dimension;
    private final Camera3D camera = new Camera3D();
    private float surfaceY;
    private long openingStartNanos;

    private final Set<Integer> heldKeys = new HashSet<>();

    private List<FormattedCharSequence> controlsLines;
    private boolean controlsDebug;
    private long cacheTextRevision = -1;
    private List<FormattedCharSequence> cacheLines = List.of();

    public LodViewerScreen() {
        super(Component.translatable("screen.immersive_worldmap.title"));

        this.minecraft = Minecraft.getInstance();

        boolean previousCaveView = SharedSettings.caveView;
        int previousBaselineY = SharedSettings.caveViewBaselineY;
        int previousWallHeight = SharedSettings.caveViewWallHeight;

        loadState();

        if (previousCaveView != SharedSettings.caveView
            || (SharedSettings.caveView && (previousBaselineY != SharedSettings.caveViewBaselineY || previousWallHeight != SharedSettings.caveViewWallHeight))) {
            clearMeshes();
        }
    }

    @Override
    protected void init() {
        super.init();

        controlsLines = null;
        cacheLines = List.of();
        cacheTextRevision = -1;
    }

    private void loadState() {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        dimension = minecraft.level.dimension().location().toString();
        camera.setTarget((float) minecraft.player.getX(),
                (float) (minecraft.player.getY() - minecraft.level.getMinBuildHeight()),
                (float) minecraft.player.getZ());
        SharedSettings.caveViewBaselineY = (int) Math.floor(minecraft.player.getY()) - minecraft.level.getMinBuildHeight();

        BlockPos.MutableBlockPos aboveHead = new BlockPos.MutableBlockPos(minecraft.player.getBlockX(),
                (int) Math.ceil(minecraft.player.getBoundingBox().maxY), minecraft.player.getBlockZ());
        int airBlocks = 0;
        while (airBlocks < MAX_CAVE_AIR_BLOCKS && !minecraft.level.isOutsideBuildHeight(aboveHead) && minecraft.level.getBlockState(aboveHead).isAir()) {
            airBlocks++;
            aboveHead.move(0, 1, 0);
        }
        SharedSettings.caveViewWallHeight = airBlocks;

        SharedSettings.caveView = false;
        boolean detectCaves = Config.getInstance().isCaveViewEnabled() && Config.getInstance().autoCaveView;
        for (; detectCaves && aboveHead.getY() < minecraft.level.getMaxBuildHeight(); aboveHead.move(0, 1, 0)) {
            BlockState state = minecraft.level.getBlockState(aboveHead);
            if (state.blocksMotion() && !(state.getBlock() instanceof LeavesBlock)) {
                SharedSettings.caveView = true;
                break;
            }
        }

        surfaceY = SharedSettings.caveView ? camera.getSmoothTargetY() : Math.min(camera.getSmoothTargetY(),
                minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, minecraft.player.getBlockX(), minecraft.player.getBlockZ())
                - minecraft.level.getMinBuildHeight());

        camera.setZoom(SharedSettings.caveView ? 150f : 200f);
        camera.snapRotation(playerFacingYaw ? -minecraft.player.getYRot() : DEFAULT_YAW, SharedSettings.caveView ? -60 : -45f);
    }

    private void clearMeshes() {
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();
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
        if (ImmersiveWorldmapClient.MAP_VIEWER_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_F1) {
            if (heldKeys.add(keyCode)) {
                minecraft.options.hideGui = !minecraft.options.hideGui;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_F3) {
            if (heldKeys.add(keyCode)) {
                minecraft.getDebugOverlay().toggleOverlay();
            }
            return true;
        }

        // Debug-only actions, gated behind the F3 overlay.
        if (minecraft.getDebugOverlay().showDebugScreen()) {
            if (keyCode == GLFW.GLFW_KEY_F6) {
                MapDebug.clearDimensionLods(dimension);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F7) {
                MapDebug.clearGeneratedLods(dimension);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F8) {
                if (heldKeys.add(keyCode)) {
                    MapDebug.reprocessGeneratedChunks();
                }
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_C && Config.getInstance().isCaveViewEnabled()) {
            if (heldKeys.add(keyCode)) {
                SharedSettings.caveView = !SharedSettings.caveView;
                clearMeshes();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            if (heldKeys.add(keyCode)) {
                playerFacingYaw = !playerFacingYaw;
                if (minecraft.player != null) {
                    camera.setRotation(playerFacingYaw ? -minecraft.player.getYRot() : DEFAULT_YAW, camera.getSmoothPitch());
                }
            }
            return true;
        }

        if (camera.keyPressed(keyCode)) {
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
        if (minecraft.level == null || minecraft.player == null) {
            onClose();
            return;
        }
        if (!minecraft.level.dimension().location().toString().equals(dimension)) {
            loadState();
            clearMeshes();
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        long now = System.nanoTime();
        if (openingStartNanos == 0) {
            openingStartNanos = now;
        }
        float openingProgress = Config.getInstance().openingAnimation
                ? Math.min((now - openingStartNanos) / (OPENING_DURATION_SECONDS * 1_000_000_000f), 1f) : 1f;
        float openingBonus = 1f - openingProgress * openingProgress * (3f - 2f * openingProgress);

        camera.tick();
        TickLruCache.tick();

        float zoom = camera.getSmoothZoom() * (1f + 0.3f * openingBonus);
        float yawDegrees = camera.getSmoothYaw() + 10f * openingBonus;
        Matrix4f mv, proj;
        LodChunkVisibilitySelector.RenderRange renderRange;
        {
            float pitchDegrees = camera.getSmoothPitch() - 10f * openingBonus;
            float yaw = (float) Math.toRadians(yawDegrees);
            float pitch = (float) Math.toRadians(pitchDegrees);
            float targetX = camera.getSmoothTargetX();
            float targetY = camera.getSmoothTargetY();
            float targetZ = camera.getSmoothTargetZ();
            renderRange = LodChunkVisibilitySelector.renderRange(zoom, pitchDegrees, Math.max(0, targetY - surfaceY));

            float eyeX = targetX - (float) (Math.sin(yaw) * Math.cos(pitch)) * zoom;
            float eyeY = targetY - (float) (Math.sin(pitch)) * zoom;
            float eyeZ = targetZ - (float) (Math.cos(yaw) * Math.cos(pitch)) * zoom;

            mv = new Matrix4f().lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0, 1, 0);
            proj = new Matrix4f().setPerspective((float) Math.toRadians(60.0), (float) width / height,
                    zoom * 0.1f, Math.max(zoom * 10f, renderRange.end() * 2f));

            LodChunkVisibilitySelector.get().update(dimension, minecraft.level.getHeight(), targetX, targetY, targetZ,
                    yawDegrees, pitchDegrees, zoom, (float) width / height, renderRange.end());
        }

        graphics.flush();
        RenderSystem.depthMask(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        LodChunkPageManager.INSTANCE.draw(LodChunkVisibilitySelector.get().selection(), mv, proj,
                camera.getSmoothTargetX(), camera.getSmoothTargetZ(),
                renderRange);

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        renderPlayers(graphics, mv, proj);

        if (!minecraft.options.hideGui) {
            drawDebug(graphics, yawDegrees);
        }

        int fadeAlpha = Math.round(openingBonus * 255f);
        if (fadeAlpha > 0) {
            graphics.fill(0, 0, this.width, this.height, fadeAlpha << 24);
        }
    }

    private void drawDebug(GuiGraphics graphics, float yawDegrees) {
        boolean debug = minecraft.getDebugOverlay().showDebugScreen();
        List<FormattedCharSequence> controls = Config.getInstance().showControls || debug ? controls(debug) : List.of();

        if (debug) {
            int tasks = ChunkLodProcessor.getPendingTaskCount() + LodChunkPageManager.INSTANCE.getPendingTaskCount();
            drawOverlay(graphics, Component.translatable("screen.immersive_worldmap.tasks", tasks).getVisualOrderText(), 20);
            if (cacheTextRevision != MapMemoryTracker.revision()) {
                var cacheText = Component.translatable("screen.immersive_worldmap.cache.colors", MapMemoryTracker.colorCacheSize())
                        .append(" | ").append(Component.translatable("screen.immersive_worldmap.cache.cpu",
                                MapMemoryTracker.cpuCacheSize(), MapMemoryTracker.cpuCacheBudget()))
                        .append(" | ").append(Component.translatable("screen.immersive_worldmap.cache.pages",
                                MapMemoryTracker.pageGpuSize(), MapMemoryTracker.pageRamSize()))
                        .append(" | ").append(Component.translatable("screen.immersive_worldmap.cache.recording", MapMemoryTracker.recordingQueueSize()));
                cacheLines = font.split(cacheText, Math.max(1, width - 40));
                cacheTextRevision = MapMemoryTracker.revision();
            }

            int cacheY = 20 + font.lineHeight + OVERLAY_PADDING * 2 + 2;
            for (FormattedCharSequence line : cacheLines) {
                drawOverlay(graphics, line, cacheY);
                cacheY += font.lineHeight + OVERLAY_PADDING * 2 + 2;
            }
        }

        int lineHeight = font.lineHeight + OVERLAY_PADDING * 2 + 2;
        int controlsY = height - 20 - (controls.size() - 1) * lineHeight;
        for (FormattedCharSequence line : controls) {
            drawOverlay(graphics, line, controlsY);
            controlsY += lineHeight;
        }

        MapCompass.render(graphics, font, yawDegrees, width, height);
    }

    private List<FormattedCharSequence> controls(boolean debug) {
        if (controlsLines != null && controlsDebug == debug) {
            return controlsLines;
        }

        var controlsText = Component.translatable("screen.immersive_worldmap.controls.pan_drag")
                .append("   ").append(Component.translatable("screen.immersive_worldmap.controls.orbit_drag"))
                .append("   ").append(Component.translatable("screen.immersive_worldmap.controls.zoom"))
                .append("   ").append(Component.translatable("screen.immersive_worldmap.controls.pan_keys"))
                .append("   ").append(Component.translatable("screen.immersive_worldmap.controls.rotate"))
                .append("   ").append(Component.translatable("screen.immersive_worldmap.controls.align_view"));

        if (Config.getInstance().isCaveViewEnabled()) {
            controlsText.append("   ").append(Component.translatable("screen.immersive_worldmap.controls.cave_view"));
        }
        controlsText.append("   ").append(Component.translatable("screen.immersive_worldmap.controls.close",
                ImmersiveWorldmapClient.MAP_VIEWER_KEY.getTranslatedKeyMessage()));

        if (debug) {
            controlsText.append("\n").append(Component.translatable("screen.immersive_worldmap.clear_dimension"))
                    .append("   ").append(Component.translatable("screen.immersive_worldmap.clear_generated"))
                    .append("   ").append(Component.translatable("screen.immersive_worldmap.reprocess_generated"));
        }

        controlsLines = font.split(controlsText, Math.max(1, width - 80));
        controlsDebug = debug;
        return controlsLines;
    }

    private void drawOverlay(GuiGraphics graphics, FormattedCharSequence text, int y) {
        int x = 20;
        graphics.fill(x - OVERLAY_PADDING, y - OVERLAY_PADDING,
                x + this.font.width(text) + OVERLAY_PADDING, y + this.font.lineHeight + OVERLAY_PADDING,
                0x80000000);
        graphics.drawString(this.font, text, x, y, 0xFFFFFFFF);
    }

    private void renderPlayers(GuiGraphics graphics, Matrix4f mv, Matrix4f proj) {
        if (minecraft.level == null || !minecraft.level.dimension().location().toString().equals(dimension)) {
            return;
        }

        Matrix4f viewProjection = new Matrix4f(proj).mul(mv);
        Vector4f position = new Vector4f();
        Vector4f facing = new Vector4f();
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

                // Projected pointing arrow thingy
                float playerYaw = (float) Math.toRadians(player.getViewYRot(partialTick));
                facing.set(-(float) Math.sin(playerYaw), 0f, (float) Math.cos(playerYaw), 0f);
                viewProjection.transform(facing);
                float directionX = (facing.x * position.w - position.x * facing.w) * width;
                float directionY = -(facing.y * position.w - position.y * facing.w) * height;
                drawPlayerFacingArrow(graphics, directionX, directionY);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    private void drawPlayerFacingArrow(GuiGraphics graphics, float directionX, float directionY) {
        float length = (float) Math.hypot(directionX, directionY);
        if (length == 0f) return;
        directionX /= length;
        directionY /= length;

        float center = PLAYER_MARKER_SIZE / 2f;
        float offset = center + 7f;
        graphics.pose().pushPose();
        graphics.pose().translate(center + directionX * offset, center + directionY * offset, 0f);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(directionX, -directionY)));
        graphics.blit(PLAYER_FACING_ARROW, -4, -3, 0f, 0f, 9, 6, 9, 6);
        graphics.pose().popPose();
    }

    @Override
    public void added() {
        super.added();
        ImmersiveWorldmapClient.onMapOpened();
    }

    @Override
    public void removed() {
        ImmersiveWorldmapClient.onMapClosed();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
