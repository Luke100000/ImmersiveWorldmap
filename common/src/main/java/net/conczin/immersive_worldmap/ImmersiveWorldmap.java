package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class ImmersiveWorldmap {
    public static final String MOD_ID = "immersive_worldmap";
    public static final String MOD_NAME = "Immersive Worldmap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public static void init() {
        // No-op
    }

    public static void shutdown() {
        ChunkLodProcessor.shutdown();
        DatabaseManager.shutdown();
    }

    public static void start() {
        ChunkLodProcessor.start();

        if (DatabaseManager.isInitialized()) {
            DatabaseManager.shutdown();
        }

        String identifier;
        ServerData currentServer = Minecraft.getInstance().getCurrentServer();
        IntegratedServer singleplayerServer = Minecraft.getInstance().getSingleplayerServer();

        if (currentServer != null) {
            identifier = currentServer.ip;
        } else if (singleplayerServer != null) {
            identifier = singleplayerServer.getWorldData().getLevelName();
        } else {
            identifier = "unknown_world";
        }

        Path dbPath = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("immersiveworldmap")
                .resolve(identifier + ".db");
        DatabaseManager.initialize(dbPath);
    }
}