package net.conczin.immersive_worldmap.lod;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.database.ChunkLodDatabase;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.util.ThreadPoolUtil;
import net.conczin.immersive_worldmap.util.PriorityThreadPoolExecutor;
import net.conczin.immersive_worldmap.util.TickLruCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Processes chunks and generates LODs.
 */
public class ChunkLodProcessor {
    public static PriorityThreadPoolExecutor EXECUTOR;

    private static final TickLruCache<CacheKey, LodChunkData> LOD_CACHE = new TickLruCache<>(256);
    private static final Object LOD_LOCK = new Object();
    private static final Map<CacheKey, LodRequest> IN_FLIGHT = new HashMap<>();

    private static final class LodRequest {
        final CompletableFuture<LodChunkData> future = new CompletableFuture<>();
        long revision;
    }

    public static void start() {
        EXECUTOR = ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap");
        synchronized (LOD_LOCK) {
            LOD_CACHE.clear();
            IN_FLIGHT.clear();
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
        EXECUTOR.close();
    }

    public static void processChunk(LevelChunk chunk, long packetHash) {
        EXECUTOR.submit(0, () -> {
            processChunkSync(chunk, packetHash);
            return null;
        });
    }

    private static int getBlockIndex(int height, int x, int y, int z) {
        return (x * height * 16) + (y * 16) + z;
    }

    private static void processChunkSync(LevelChunk chunk, long packetHash) {
        if (!DatabaseManager.isInitialized()) {
            return;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        String dimension = chunk.getLevel().dimension().location().toString();

        // Skip chunks whose packet bytes are identical to the last ingest
        try {
            Long storedHash = DatabaseManager.getInstance().getPacketHash(chunkX, chunkZ, dimension, 0);
            if (storedHash != null && storedHash == packetHash) {
                return;
            }
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to read chunk packet hash: {}", e.getMessage());
        }

        // Chunk is empty
        if (chunk.isEmpty()) {
            upsertChunkData(chunkX, chunkZ, dimension, 0, null, 0, packetHash);
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
            upsertChunkData(chunkX, chunkZ, dimension, 0, null, 0, packetHash);
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

        upsertChunkData(chunkX, chunkZ, dimension, 0, chunkData, findMinSurface(chunkData), packetHash);
    }

    // Lowest surface height in the chunk, 0 when no column has one
    private static int findMinSurface(byte[] data) {
        int height = data.length / 256;
        int min = Integer.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = 0;
                for (int y = height - 1; y > 0; y--) {
                    boolean skyAbove = y + 1 >= height || data[x * height * 16 + (y + 1) * 16 + z] == 0;
                    if (skyAbove && data[x * height * 16 + y * 16 + z] != 0) {
                        top = y;
                        break;
                    }
                }
                min = Math.min(min, top);
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static void upsertChunkData(int chunkX, int chunkZ, String dimension, int lod, byte[] data, int minSurface, long packetHash) {
        upsertChunkData(new CacheKey(chunkX, chunkZ, dimension, lod), data, minSurface, packetHash, null, 0);
    }

    private static void upsertChunkData(CacheKey key, byte[] data, int minSurface, long packetHash,
                                        LodRequest request, long revision) {
        synchronized (LOD_LOCK) {
            // A source may have changed while the LOD's children were being loaded or downsampled.
            if (request != null && (IN_FLIGHT.get(key) != request || request.revision != revision)) {
                return;
            }
            try {
                DatabaseManager.getInstance().upsertChunk(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), data, minSurface, packetHash);
                int x = key.chunkX();
                int z = key.chunkZ();
                for (int lod = key.lod(); lod <= LodChunkData.MAX_LOD; lod++) {
                    CacheKey parent = new CacheKey(x, z, key.dimension(), lod);
                    LOD_CACHE.remove(parent);
                    LodRequest pending = IN_FLIGHT.get(parent);
                    if (pending != null && pending != request) {
                        pending.revision++;
                    }
                    x = Math.floorDiv(x, 2);
                    z = Math.floorDiv(z, 2);
                }
                LodChunkMeshManager.INSTANCE.invalidate(key.chunkX(), key.chunkZ(), key.lod(), key.dimension());
            } catch (SQLException e) {
                ImmersiveWorldmap.LOGGER.warn("Failed to store chunk LOD data: {}", e.getMessage());
            }
        }
    }

    private static CompletableFuture<LodChunkData> loadLodAsync(CacheKey key, LodRequest request, long revision) {
        return EXECUTOR.submit(key.lod(), key, () -> loadStoredChunk(key)).thenCompose(stored -> {
            if (stored.exists() && !stored.dirty()) {
                return CompletableFuture.completedFuture(new LodChunkData(
                        key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), stored.colors(), stored.minSurface()));
            }
            if (key.lod() == 0) {
                return CompletableFuture.completedFuture(new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), 0, null, 0));
            }
            return generateLodAsync(key, request, revision);
        });
    }

    private static ChunkLodDatabase.StoredChunk loadStoredChunk(CacheKey key) {
        if (!DatabaseManager.isInitialized()) {
            return new ChunkLodDatabase.StoredChunk(false, null, false, 0);
        }
        try {
            return DatabaseManager.getInstance().loadChunk(key.chunkX(), key.chunkZ(), key.dimension(), key.lod());
        } catch (SQLException e) {
            ImmersiveWorldmap.LOGGER.warn("Failed to retrieve chunk LOD data: {}", e.getMessage());
            return new ChunkLodDatabase.StoredChunk(false, null, false, 0);
        }
    }

    private static CompletableFuture<LodChunkData> generateLodAsync(CacheKey key, LodRequest request, long revision) {
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
                upsertChunkData(key, null, 0, 0L, request, revision);
                return CompletableFuture.completedFuture(new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), null, 0));
            }
            return EXECUTOR.submit(key.lod(), key, () -> {
                LodChunkData[][] data = {{sources.get(0).join(), sources.get(2).join()}, {sources.get(1).join(), sources.get(3).join()}};
                byte[] result = generateLod(data);
                int minSurface = findMinSurface(result);
                upsertChunkData(key, result, minSurface, 0L, request, revision);
                return new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), result, minSurface);
            });
        });
    }

    private static byte[] generateLod(LodChunkData[][] sources) {
        // Fetch the fixed 2x2 grid of source chunks from the previous LOD level
        int sourceHeight = 0;
        for (LodChunkData[] sourceColumn : sources) {
            for (LodChunkData source : sourceColumn) {
                sourceHeight = Math.max(sourceHeight, source.getHeight());
            }
        }
        int outHeight = Math.max(1, sourceHeight / 2);
        byte[] result = new byte[16 * outHeight * 16];

        int[] freq = new int[256];
        int[] values = new int[8];

        // For all 4 parent chunks
        for (int cx = 0; cx < 2; cx++) {
            for (int cz = 0; cz < 2; cz++) {
                LodChunkData src = sources[cx][cz];
                if (src.empty()) {
                    continue;
                }

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
        LodRequest request;
        synchronized (LOD_LOCK) {
            LodChunkData cached = LOD_CACHE.get(key);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            LodRequest existing = IN_FLIGHT.get(key);
            if (existing != null) {
                return existing.future;
            }
            request = new LodRequest();
            IN_FLIGHT.put(key, request);
        }
        loadRequest(key, request);
        return request.future;
    }

    private static void loadRequest(CacheKey key, LodRequest request) {
        long revision;
        synchronized (LOD_LOCK) {
            revision = request.revision;
        }
        loadLodAsync(key, request, revision).whenComplete((data, error) -> {
            boolean retry;
            synchronized (LOD_LOCK) {
                if (IN_FLIGHT.get(key) != request) {
                    return;
                }
                retry = error == null && request.revision != revision;
                if (!retry) {
                    if (error == null) {
                        LOD_CACHE.put(key, data);
                    }
                    IN_FLIGHT.remove(key);
                }
            }
            if (retry) {
                loadRequest(key, request);
            } else if (error == null) {
                request.future.complete(data);
            } else {
                request.future.completeExceptionally(error);
            }
        });
    }

    public static void discardQueuedTasksOutside(Set<CacheKey> visibleKeys) {
        EXECUTOR.discardQueuedTasksOutside(tag -> tag instanceof CacheKey key && isCoveredByViewport(key, visibleKeys));
    }

    public static void clearQueuedViewportTasks() {
        EXECUTOR.discardQueuedTasksOutside(tag -> false);
    }

    private static boolean isCoveredByViewport(CacheKey key, Set<CacheKey> visibleKeys) {
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        for (int lod = key.lod(); lod <= LodChunkData.MAX_LOD; lod++) {
            if (visibleKeys.contains(new CacheKey(chunkX, chunkZ, key.dimension(), lod))) {
                return true;
            }
            chunkX = Math.floorDiv(chunkX, 2);
            chunkZ = Math.floorDiv(chunkZ, 2);
        }
        return false;
    }

    public record CacheKey(int chunkX, int chunkZ, String dimension, int lod) {
    }
}
