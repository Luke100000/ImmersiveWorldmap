package net.conczin.immersive_worldmap.lod;

/**
 * Record containing LOD chunk data and metadata.
 * Chunks are 2D vertical columns: 16 x N x 16, where N depends on world height and LOD level
 *
 * @param chunkX chunk X coordinate
 * @param chunkZ chunk Z coordinate
 * @param dimension dimension identifier
 * @param lodLevel LOD level (0 = highest detail)
 * @param data byte array (0=air, 1=non-air)
 */
public record LodChunkData(
    int chunkX,
    int chunkZ,
    String dimension,
    int lodLevel,
    byte[] data
) {
    /**
     * Gets the height of this chunk deduced from data length.
     * Height = data.length / (16 * 16)
     */
    public int getHeight() {
        if (data == null) {
            return 0;
        }
        return data.length / 256; // 256 = 16 * 16
    }

    /**
     * Gets the block value at the given coordinates.
     *
     * @param x local x (0-15)
     * @param y local y (0-height)
     * @param z local z (0-15)
     * @return block value (0=air, 1=non-air)
     */
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

    /**
     * Checks if a block is air.
     *
     * @param x local x (0-15)
     * @param y local y (0-height)
     * @param z local z (0-15)
     * @return true if air
     */
    public boolean isAir(int x, int y, int z) {
        return getBlock(x, y, z) == 0;
    }
}

