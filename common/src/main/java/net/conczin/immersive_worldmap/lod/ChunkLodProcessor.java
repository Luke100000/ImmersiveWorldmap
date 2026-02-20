package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.sql.SQLException;
import java.util.concurrent.ExecutorService;

/**
 * Processes chunks and generates LODs.
 */
public class ChunkLodProcessor {
    public static final ExecutorService EXECUTOR = ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap");

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public static void processChunk(LevelChunk chunk) {
        EXECUTOR.submit(() -> processChunkSync(chunk));
    }

    private static void processChunkSync(LevelChunk chunk) {
        if (!DatabaseManager.isInitialized()) {
            return;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        String dimension = chunk.getLevel().dimension().location().toString();

        // Full vertical chunk: 16 x height x 16 bytes for LOD 0
        int height = chunk.getHeight();
        byte[] chunkData = new byte[16 * height * 16];

        // Process all blocks in the chunk column
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int color = section.getBlockState(x, y, z).getBlock().defaultMapColor().id;
                        int ay = (sectionIndex * 16) + y;
                        int blockIndex = (x * height * 16) + (ay * 16) + z;
                        chunkData[blockIndex] = (byte) color;
                    }
                }
            }
        }

        try {
            DatabaseManager.getInstance().upsertChunk(chunkX, chunkZ, dimension, 0, chunkData);
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to store chunk LOD data: {}", e.getMessage());
        }
    }

    /**
     * Retrieves chunk LOD data from the database.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param dimension dimension identifier
     * @param lod       LOD level
     * @return binary data, or null if not found
     */
    public static byte[] getChunkLodData(int chunkX, int chunkZ, String dimension, int lod) {
        if (!DatabaseManager.isInitialized()) {
            return null;
        }

        try {
            return DatabaseManager.getInstance().getChunkColors(chunkX, chunkZ, dimension, lod);
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to retrieve chunk LOD data: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Retrieves chunk LOD data as a LodChunkData record.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param dimension dimension identifier
     * @param lod       LOD level
     * @return LodChunkData record, or null if not found
     */
    public static LodChunkData getLodChunkData(int chunkX, int chunkZ, String dimension, int lod) {
        byte[] data = getChunkLodData(chunkX, chunkZ, dimension, lod);
        if (data != null) {
            return new LodChunkData(chunkX, chunkZ, dimension, lod, data);
        }
        return null;
    }
}
