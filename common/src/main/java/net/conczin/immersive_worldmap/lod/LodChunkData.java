package net.conczin.immersive_worldmap.lod;

/**
 * Record containing LOD chunk data and metadata.
 * Chunks are 2D vertical columns: 16 x N x 16, where N depends on world height and LOD level
 *
 * @param chunkX    chunk X coordinate
 * @param chunkZ    chunk Z coordinate
 * @param dimension dimension identifier
 * @param lodLevel  LOD level (0 = highest detail)
 * @param data      byte array (0 = air, other values represent block colors)
 */
public record LodChunkData(
        int chunkX,
        int chunkZ,
        String dimension,
        int lodLevel,
        byte[] data
) {
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
        int index = (x * height * 16) + (y * 16) + z;
        if (index >= 0 && index < data.length) {
            return data[index];
        }
        return 0;
    }

    public boolean empty() {
        return data == null;
    }
}

