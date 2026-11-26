package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.config.Config;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.conczin.immersive_worldmap.renderer.MapMemoryTracker;
import net.conczin.immersive_worldmap.util.PooledBytes;
import net.conczin.immersive_worldmap.util.TickLruCache;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ImmersiveWorldmapClient {
    public static final KeyMapping MAP_VIEWER_KEY = new KeyMapping(
            "key.immersive_worldmap.open_lod_viewer", GLFW.GLFW_KEY_M, "category.immersive_worldmap");

    private static final double RETENTION_RADIUS = 675;

    private static long closedAt;
    private static long lastMaintenance;
    private static double closedX;
    private static double closedZ;
    private static String closedDimension;
    private static boolean mapOpen;

    private ImmersiveWorldmapClient() {
    }

    public static void init() {
        ChunkLodProcessor.setInvalidationListener(key -> LodChunkMeshManager.INSTANCE.invalidate(
                key.chunkX(), key.chunkZ(), key.lod(), key.dimension()));
    }

    public static void onClientTick() {
        TickLruCache.tick();
        long now = System.nanoTime();
        if (now - lastMaintenance < TimeUnit.SECONDS.toNanos(1)) return;
        lastMaintenance = now;

        Minecraft minecraft = Minecraft.getInstance();
        if (!mapOpen && closedAt != 0 && minecraft.player != null && minecraft.level != null) {
            double dx = minecraft.player.getX() - closedX;
            double dz = minecraft.player.getZ() - closedZ;
            if (now - closedAt >= TimeUnit.MINUTES.toNanos(Config.getInstance().retentionMinutes)
                || !minecraft.level.dimension().location().toString().equals(closedDimension)
                || dx * dx + dz * dz > RETENTION_RADIUS * RETENTION_RADIUS) {
                LodChunkPageManager.INSTANCE.clear();
                LodChunkMeshManager.INSTANCE.prune(key -> false);
                ChunkLodProcessor.clearCache();
                closedAt = 0;
            }
        }

        MapMemoryTracker.update();
    }

    public static void onMapOpened() {
        mapOpen = true;
        closedAt = 0;
        PooledBytes.setActive(true);
        LodChunkPageManager.INSTANCE.setActive(true);
    }

    public static void onMapClosed() {
        PooledBytes.setActive(false);
        LodChunkVisibilitySelector.reset();
        ChunkLodProcessor.clearQueuedViewportTasks();
        LodChunkPageManager.INSTANCE.setActive(false);

        mapOpen = false;

        Minecraft minecraft = Minecraft.getInstance();
        Predicate<ChunkLodProcessor.CacheKey> keep = key -> false;
        if (minecraft.player != null && minecraft.level != null) {
            closedX = minecraft.player.getX();
            closedZ = minecraft.player.getZ();
            closedDimension = minecraft.level.dimension().location().toString();
            closedAt = System.nanoTime();
            keep = key -> key.dimension().equals(closedDimension);
        }
        LodChunkMeshManager.INSTANCE.prune(keep);
        ChunkLodProcessor.pruneCache(keep);
    }

    public static boolean shouldRecordChunks() {
        return !ServerChunkRecorder.isRunning();
    }

    public static void start() {
        closedAt = 0;

        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();

        if (shouldRecordChunks()) {
            ChunkLodProcessor.shutdown();
            DatabaseManager.shutdown();

            // Start database and processor
            ServerData server = Minecraft.getInstance().getCurrentServer();
            String name = server == null ? ImmersiveWorldmap.databaseName("unknown", "world")
                    : ImmersiveWorldmap.databaseName("multiplayer", server.ip);
            Path dbPath = Minecraft.getInstance().gameDirectory.toPath().resolve("immersiveworldmap").resolve(name);
            DatabaseManager.initialize(dbPath);
            ChunkLodProcessor.start();
        }
    }

    public static void shutdown() {
        closedAt = 0;
        mapOpen = false;

        PooledBytes.setActive(false);
        LodChunkPageManager.INSTANCE.setActive(false);
        LodChunkVisibilitySelector.reset();
        ChunkLodProcessor.clearQueuedViewportTasks();
        if (shouldRecordChunks()) {
            ChunkLodProcessor.shutdown();
            DatabaseManager.shutdown();
        }
        LodChunkMeshManager.INSTANCE.clear();
    }
}
