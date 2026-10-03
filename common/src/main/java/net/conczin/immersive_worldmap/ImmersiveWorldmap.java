package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ImmersiveWorldmap {
    public static final String MOD_ID = "immersive_worldmap";
    public static final String MOD_NAME = "Immersive Worldmap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public static final KeyMapping MAP_VIEWER_KEY = new KeyMapping(
            "key.immersive_worldmap.open_lod_viewer",
            GLFW.GLFW_KEY_M,
            "category.immersive_worldmap");

    public static void init() {
        // No-op
    }

    public static void onClientTick() {
        LodChunkMeshManager.tick();
    }

    public static void shutdown() {
        LodChunkVisibilitySelector.reset();
        ChunkLodProcessor.shutdown();
        DatabaseManager.shutdown();
        LodChunkMeshManager.INSTANCE.clear();
    }

    public static void start() {
        LodChunkVisibilitySelector.reset();
        if (DatabaseManager.isInitialized()) {
            ChunkLodProcessor.shutdown();
            DatabaseManager.shutdown();
        }
        LodChunkMeshManager.INSTANCE.clear();

        String identifier = getWorldIdentifier();

        Path dbPath = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("immersiveworldmap")
                .resolve(identifier + ".db");
        DatabaseManager.initialize(dbPath);
        ChunkLodProcessor.start();
    }

    private static String getWorldIdentifier() {
        ServerData currentServer = Minecraft.getInstance().getCurrentServer();
        IntegratedServer singleplayerServer = Minecraft.getInstance().getSingleplayerServer();

        if (currentServer != null) {
            return currentServer.ip;
        } else if (singleplayerServer != null) {
            return singleplayerServer.getWorldData().getLevelName();
        } else {
            return "unknown_world";
        }
    }
}
