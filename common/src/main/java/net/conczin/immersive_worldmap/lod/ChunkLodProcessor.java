package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.conczin.immersive_worldmap.util.PriorityThreadPoolExecutor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes chunks and generates LODs.
 */
public class ChunkLodProcessor {
    public static PriorityThreadPoolExecutor EXECUTOR;

    private static final int LOD_CACHE_SIZE = 256;
    private static final Map<CacheKey, LodChunkData> LOD_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(LOD_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, LodChunkData> eldest) {
                    return size() > LOD_CACHE_SIZE;
                }
            }
    );
    private static final Map<CacheKey, CompletableFuture<LodChunkData>> IN_FLIGHT = new ConcurrentHashMap<>();

    public static void start() {
        EXECUTOR = ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap");
        LOD_CACHE.clear();
        IN_FLIGHT.clear();
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public static void clearDimension(String dimension) {
        if (!DatabaseManager.isInitialized()) {
            return;
        }

        try {
            DatabaseManager.getInstance().clearDimension(dimension);
            synchronized (LOD_CACHE) {
                LOD_CACHE.keySet().removeIf(key -> key.dimension().equals(dimension) && key.lod() > 0);
            }
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to delete generated LOD data: {}", e.getMessage());
        }
    }

    public static void processChunk(LevelChunk chunk) {
        EXECUTOR.submit(0, () -> {
            processChunkSync(chunk);
            return null;
        });
    }

    private static int getBlockIndex(int height, int x, int y, int z) {
        return (x * height * 16) + (y * 16) + z;
    }

    private static void processChunkSync(LevelChunk chunk) {
        if (!DatabaseManager.isInitialized()) {
            return;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        String dimension = chunk.getLevel().dimension().location().toString();

        // Chunk is empty
        if (chunk.isEmpty()) {
            upsertChunkData(chunkX, chunkZ, dimension, 0, null);
            return;
        }

        // Check if at least one section is not empty
        boolean empty = true;
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            if (!section.hasOnlyAir()) {
                empty = false;
                break;
            }
        }
        if (empty) {
            upsertChunkData(chunkX, chunkZ, dimension, 0, null);
            return;
        }

        // Full vertical chunk: 16 x height x 16 bytes for LOD 0
        int height = chunk.getHeight();
        byte[] chunkData = new byte[16 * height * 16];

        // Process all blocks in the chunk column
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
                        int blockIndex = getBlockIndex(height, x, ay, z);
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

    private static CompletableFuture<LodChunkData> loadLodAsync(CacheKey key) {
        return EXECUTOR.submit(key.lod(), () -> loadStoredLod(key)).thenCompose(stored -> {
            if (stored != null || key.lod() == 0) {
                return CompletableFuture.completedFuture(new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), stored));
            }
            return generateLodAsync(key);
        });
    }

    private static byte[] loadStoredLod(CacheKey key) {
        if (!DatabaseManager.isInitialized()) return null;
        try {
            return DatabaseManager.getInstance().getChunkColors(key.chunkX(), key.chunkZ(), key.dimension(), key.lod());
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to retrieve chunk LOD data: {}", e.getMessage());
            return null;
        }
    }

    private static CompletableFuture<LodChunkData> generateLodAsync(CacheKey key) {
        int baseX = key.chunkX() * 2;
        int baseZ = key.chunkZ() * 2;
        List<CompletableFuture<LodChunkData>> sources = List.of(
                getLodChunkDataAsync(baseX, baseZ, key.dimension(), key.lod() - 1),
                getLodChunkDataAsync(baseX + 1, baseZ, key.dimension(), key.lod() - 1),
                getLodChunkDataAsync(baseX, baseZ + 1, key.dimension(), key.lod() - 1),
                getLodChunkDataAsync(baseX + 1, baseZ + 1, key.dimension(), key.lod() - 1)
        );
        return CompletableFuture.allOf(sources.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            if (sources.stream().map(CompletableFuture::join).allMatch(LodChunkData::empty)) {
                upsertChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), null);
                return CompletableFuture.completedFuture(new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), null));
            }
            return EXECUTOR.submit(key.lod(), () -> {
                LodChunkData[][] data = {{sources.get(0).join(), sources.get(2).join()}, {sources.get(1).join(), sources.get(3).join()}};
                byte[] result = generateLod(data);
                upsertChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), result);
                return new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), result);
            });
        });
    }

    private static byte[] generateLod(LodChunkData[][] sources) {
        // Fetch the fixed 2x2 grid of source chunks from the previous LOD level
        int outHeight = Math.max(1, sources[0][0].getHeight() / 2);
        byte[] result = new byte[16 * outHeight * 16];

        int[] freq = new int[256];
        int[] values = new int[8];

        // For all 4 parent chunks
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) {
                LodChunkData src = sources[cx][cz];
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
                                        byte top = src.getBlock(x * 2 + dx, y * 2 + dy + 1, z * 2 + dz);
                                        int is = i & 0xFF;
                                        // Prefer exposed blocks
                                        int c = (freq[is] += top == 0 ? 2 : 1);
                                        if (c > modeCount) {
                                            modeVal = i;
                                            modeCount = c;
                                        }
                                        values[count++] = is;
                                    }
                                }
                            }

                            for (int i = 0; i < count; i++) {
                                freq[values[i]] = 0;
                            }

                            result[getBlockIndex(outHeight, cx * 8 + x, y, cz * 8 + z)] = modeVal;
                        }
                    }
                }
            }
        }

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
    public static CompletableFuture<LodChunkData> getLodChunkDataAsync(int chunkX, int chunkZ, String dimension, int lod) {
        CacheKey key = new CacheKey(chunkX, chunkZ, dimension, lod);
        synchronized (LOD_CACHE) {
            LodChunkData cached = LOD_CACHE.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached);
        }
        return IN_FLIGHT.computeIfAbsent(key, currentKey -> {
            CompletableFuture<LodChunkData> future = loadLodAsync(currentKey);
            future.whenComplete((data, error) -> {
                if (error == null) LOD_CACHE.put(currentKey, data);
                IN_FLIGHT.remove(currentKey, future);
            });
            return future;
        });
    }

    public record CacheKey(int chunkX, int chunkZ, String dimension, int lod) {
    }
}
