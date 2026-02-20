package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Processes chunks and generates LODs.
 */
public class ChunkLodProcessor {
    public static ExecutorService EXECUTOR;

    private static final int LOD_CACHE_SIZE = 256;
    private static final Map<CacheKey, LodChunkData> LOD_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(LOD_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, LodChunkData> eldest) {
                    return size() > LOD_CACHE_SIZE;
                }
            }
    );

    public static void start() {
        EXECUTOR = ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap");
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public static void processChunk(LevelChunk chunk) {
        EXECUTOR.submit(() -> processChunkSync(chunk));
    }

    private static int getIdx(int height, int x, int y, int z) {
        return (x * height * 16) + (y * 16) + z;
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
                        int blockIndex = getIdx(height, x, ay, z);
                        chunkData[blockIndex] = (byte) color;
                    }
                }
            }
        }

        upsertChunkData(chunkX, chunkZ, dimension, 0, chunkData);
    }

    private static void upsertChunkData(int chunkX, int chunkZ, String dimension, int lod, byte[] data) {
        try {
            DatabaseManager.getInstance().upsertChunk(chunkX, chunkZ, dimension, lod, data);
            clearLodCacheForLevel(chunkX, chunkZ, dimension, lod);
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to store chunk LOD data: {}", e.getMessage());
        }
    }

    private static void clearLodCacheForLevel(int chunkX, int chunkZ, String dimension, int lod) {
        CacheKey key = new CacheKey(chunkX, chunkZ, dimension, lod);
        LOD_CACHE.remove(key);
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
            byte[] chunkColors = DatabaseManager.getInstance().getChunkColors(chunkX, chunkZ, dimension, lod);
            if (chunkColors != null) {
                return chunkColors;
            }
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to retrieve chunk LOD data: {}", e.getMessage());
        }

        if (lod > 0) {
            return generateLod(chunkX, chunkZ, dimension, lod);
        }

        return null;
    }

    private static byte[] generateLod(int chunkX, int chunkZ, String dimension, int lod) {
        // Fetch the fixed 2x2 grid of source chunks from the previous LOD level
        int baseX = chunkX * 2;
        int baseZ = chunkZ * 2;
        LodChunkData c00 = getLodChunkData(baseX, baseZ, dimension, lod - 1);
        LodChunkData c10 = getLodChunkData(baseX + 1, baseZ, dimension, lod - 1);
        LodChunkData c01 = getLodChunkData(baseX, baseZ + 1, dimension, lod - 1);
        LodChunkData c11 = getLodChunkData(baseX + 1, baseZ + 1, dimension, lod - 1);

        int maxSourceHeight = 0;
        maxSourceHeight = Math.max(maxSourceHeight, c00.getHeight());
        maxSourceHeight = Math.max(maxSourceHeight, c10.getHeight());
        maxSourceHeight = Math.max(maxSourceHeight, c01.getHeight());
        maxSourceHeight = Math.max(maxSourceHeight, c11.getHeight());

        if (maxSourceHeight == 0) {
            return null;
        }

        int outHeight = Math.max(1, maxSourceHeight / 2);
        byte[] result = new byte[16 * outHeight * 16];

        int[] freq = new int[256];
        int[] values = new int[8];

        // For all 4 parent chunks
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) {
                LodChunkData src = cx == 0 ? (cz == 0 ? c00 : c01) : (cz == 0 ? c10 : c11);
                if (src.empty()) continue;

                // And over all output bytes
                for (int x = 0; x < 8; x++) {
                    for (int z = 0; z < 8; z++) {
                        for (int y = 0; y < outHeight; y++) {
                            byte modeVal = 0;
                            int modeCount = 0;
                            int count = 0;

                            // Filter a 2x2x2 block
                            for (int dx = 0; dx < 2; dx++) {
                                for (int dy = 0; dy < 2; dy++) {
                                    for (int dz = 0; dz < 2; dz++) {
                                        byte i = src.getBlock(x * 2 + dx, y * 2 + dy, z * 2 + dz);
                                        int c = ++freq[i & 0xFF];
                                        if (c > modeCount) {
                                            modeVal = i;
                                            modeCount = c;
                                        }
                                        values[count++] = i;
                                    }
                                }
                            }

                            for (int i = 0; i < count; i++) {
                                freq[values[i]] = 0;
                            }

                            result[getIdx(outHeight, cx * 8 + x, y, cz * 8 + z)] = modeVal;
                        }
                    }
                }
            }
        }

        upsertChunkData(chunkX, chunkZ, dimension, lod, result);

        return result;
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
        CacheKey key = new CacheKey(chunkX, chunkZ, dimension, lod);
        LodChunkData cached = LOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        byte[] data = getChunkLodData(chunkX, chunkZ, dimension, lod);
        LodChunkData lodData = new LodChunkData(chunkX, chunkZ, dimension, lod, data);
        LOD_CACHE.put(key, lodData);
        return lodData;
    }

    private record CacheKey(int chunkX, int chunkZ, String dimension, int lod) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey(int otherX, int otherZ, String otherDimension, int otherLod))) {
                return false;
            }
            return chunkX == otherX
                   && chunkZ == otherZ
                   && lod == otherLod
                   && Objects.equals(dimension, otherDimension);
        }

    }
}
