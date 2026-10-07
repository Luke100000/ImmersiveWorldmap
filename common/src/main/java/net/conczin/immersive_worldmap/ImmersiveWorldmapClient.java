package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public final class ImmersiveWorldmapClient {
    public static final KeyMapping MAP_VIEWER_KEY = new KeyMapping(
            "key.immersive_worldmap.open_lod_viewer", GLFW.GLFW_KEY_M, "category.immersive_worldmap");

    private ImmersiveWorldmapClient() {
    }

    public static void init() {
        ChunkLodProcessor.setInvalidationListener(key -> LodChunkMeshManager.INSTANCE.invalidate(
                key.chunkX(), key.chunkZ(), key.lod(), key.dimension()));
    }

    public static void onClientTick() {
        LodChunkMeshManager.tick();
    }

    public static boolean shouldRecordChunks() {
        return !ServerChunkRecorder.isRunning();
    }

    public static void start() {
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();

        if (shouldRecordChunks()) {
            if (DatabaseManager.isInitialized()) {
                ChunkLodProcessor.shutdown();
                DatabaseManager.shutdown();
            }

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
        LodChunkVisibilitySelector.reset();
        ChunkLodProcessor.clearQueuedViewportTasks();
        if (shouldRecordChunks()) {
            ChunkLodProcessor.shutdown();
            DatabaseManager.shutdown();
        }
        LodChunkMeshManager.INSTANCE.clear();
    }
}
