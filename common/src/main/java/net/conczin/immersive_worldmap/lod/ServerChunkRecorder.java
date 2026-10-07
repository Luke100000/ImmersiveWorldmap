package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ServerChunkRecorder {
    private static final long UPDATE_INTERVAL_NANOS = 1_000_000_000L;
    private static volatile MinecraftServer recordingServer;
    private static final Map<DirtyChunk, Long> DIRTY_CHUNKS = new HashMap<>();

    private record DirtyChunk(ServerLevel level, ChunkPos pos) {
    }

    private ServerChunkRecorder() {
    }

    public static boolean isRunning() {
        return recordingServer != null;
    }

    public static void start(MinecraftServer server) {
        // Dedicated servers are not yet fully supported
        if (server.isDedicatedServer()) return;

        String world = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        Path dbPath = server.getServerDirectory().resolve("immersiveworldmap")
                .resolve(ImmersiveWorldmap.databaseName("singleplayer", world));
        DatabaseManager.initialize(dbPath);
        ChunkLodProcessor.start();
        recordingServer = server;
    }

    public static void record(ChunkAccess chunk, ServerLevel level) {
        if (recordingServer != level.getServer() || chunk.getPersistedStatus() != ChunkStatus.FULL) {
            return;
        }
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> record(chunk, level));
            return;
        }
        DIRTY_CHUNKS.remove(new DirtyChunk(level, chunk.getPos()));
        ChunkLodProcessor.processChunk(chunk, level);
    }

    public static void markDirty(LevelChunk chunk, ServerLevel level) {
        if (recordingServer != level.getServer() || !level.getServer().isSameThread()) {
            return;
        }
        DIRTY_CHUNKS.putIfAbsent(new DirtyChunk(level, chunk.getPos()), System.nanoTime());
    }

    public static void unload(LevelChunk chunk, ServerLevel level) {
        if (recordingServer == level.getServer() && DIRTY_CHUNKS.containsKey(new DirtyChunk(level, chunk.getPos()))) {
            record(chunk, level);
        }
    }

    public static void tick(MinecraftServer server) {
        if (recordingServer != server) return;
        long now = System.nanoTime();
        Iterator<Map.Entry<DirtyChunk, Long>> iterator = DIRTY_CHUNKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DirtyChunk, Long> entry = iterator.next();
            if (now - entry.getValue() < UPDATE_INTERVAL_NANOS) {
                continue;
            }
            DirtyChunk dirty = entry.getKey();
            iterator.remove();
            LevelChunk chunk = dirty.level().getChunkSource().getChunkNow(dirty.pos().x, dirty.pos().z);
            if (chunk != null) {
                ChunkLodProcessor.processChunk(chunk, dirty.level());
            }
        }
    }

    public static void stop(MinecraftServer server) {
        if (recordingServer != server) return;
        ChunkLodProcessor.shutdown();
        DatabaseManager.shutdown();
        DIRTY_CHUNKS.clear();
        recordingServer = null;
    }
}
