package net.conczin.immersive_worldmap.lod;

/**
 * Record containing LOD chunk data and metadata.
 * Chunks are 2D vertical columns: 16 x N x 16, where N depends on world height and LOD level
 *
 * @param chunkX     chunk X coordinate
 * @param chunkZ     chunk Z coordinate
 * @param dimension  dimension identifier
 * @param lodLevel   LOD level (0 = highest detail)
 * @param data       byte array (0 = air, other values represent block colors)
 * @param minSurface lowest height in this chunk that still has sky directly above it, 0 when there is none
 */
public record LodChunkData(
        int chunkX,
        int chunkZ,
        String dimension,
        int lodLevel,
        byte[] data,
        int minSurface
) {
    public static final int MAX_LOD = 5;

    public int getHeight() {
        if (data == null) {
            return 0;
        }
        return data.length / 256;
    }

    public byte getBlock(int x, int y, int z) {
        if (data == null) {
            return 0;
        }
        int height = getHeight();
        if (x < 0 || x >= 16 || y < 0 || y >= height || z < 0 || z >= 16) {
            return 0;
        }
        int index = (x * height * 16) + (y * 16) + z;
        return data[index];
    }

    public boolean empty() {
        return data == null;
    }
}
