package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.sql.SQLException;

/**
 * Processes chunks and generates LOD data for storage in the database.
 */
public class ChunkLodProcessor {

    /**
     * Processes a chunk and stores its LOD data in the database.
     * For each block: air = 0, non-air = 1.
     *
     * @param chunk the chunk to process
     */
    public static void processChunk(LevelChunk chunk) {
        if (chunk == null || !DatabaseManager.isInitialized()) {
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
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < 16; z++) {
                    // Determine which section this block is in
                    int sectionIndex = (y >> 4) + (chunk.getMinBuildHeight() >> 4);

                    boolean isAir = true;
                    if (sectionIndex >= 0 && sectionIndex < sections.length) {
                        LevelChunkSection section = sections[sectionIndex];
                        if (section != null && !section.hasOnlyAir()) {
                            int localY = y & 15;
                            isAir = section.getBlockState(x, localY, z).isAir();
                        }
                    }

                    // Calculate block index in the full column
                    int blockIndex = (x * height * 16) + (y * 16) + z;
                    chunkData[blockIndex] = isAir ? (byte) 0 : (byte) 1;
                }
            }
        }

        // Store in database
        try {
            DatabaseManager.getInstance().upsertChunk(
                    chunkX,
                    chunkZ,
                    dimension,
                    0, // LOD level 0 (highest detail)
                    chunkData
            );
        } catch (SQLException e) {
            System.err.println("Failed to store chunk LOD data: " + e.getMessage());
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
            System.err.println("Failed to retrieve chunk LOD data: " + e.getMessage());
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


