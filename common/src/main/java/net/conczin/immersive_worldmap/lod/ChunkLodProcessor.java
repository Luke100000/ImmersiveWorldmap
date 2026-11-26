package net.conczin.immersive_worldmap.lod;

import it.unimi.dsi.fastutil.HashCommon;
import net.conczin.immersive_worldmap.database.ChunkLodDatabase;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.util.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Processes chunks and generates LODs.
 */
public class ChunkLodProcessor {
    public static PriorityThreadPoolExecutor EXECUTOR;

    private static volatile Consumer<CacheKey> invalidationListener = key -> {
    };

    private static final TickLruCache<CacheKey, LodChunkData> LOD_CACHE = new TickLruCache<>(1024);
    private static final Object LOD_LOCK = new Object();
    private static final Map<CacheKey, LodRequest> IN_FLIGHT = new HashMap<>();
    private static final Map<CacheKey, ChunkSnapshot> CHUNK_INGESTS = new ConcurrentHashMap<>();
    private static final Set<CacheKey> RECORDING_TASKS = ConcurrentHashMap.newKeySet();

    private record ChunkSnapshot(List<PalettedContainer<BlockState>> sections, long estimatedBytes) {
    }

    private static final class LodRequest {
        final CompletableFuture<LodChunkData> future = new CompletableFuture<>();
        long revision;
    }

    public static void setInvalidationListener(Consumer<CacheKey> listener) {
        invalidationListener = listener;
    }

    public static void start() {
        CHUNK_INGESTS.clear();
        RECORDING_TASKS.clear();
        EXECUTOR = ThreadPoolUtil.createLowPriorityFixedThreadPool("ImmersiveWorldmap");
        synchronized (LOD_LOCK) {
            LOD_CACHE.clear();
            IN_FLIGHT.clear();
        }
    }

    public static void shutdown() {
        if (EXECUTOR == null) return;

        clearQueuedViewportTasks();

        EXECUTOR.shutdown();
        EXECUTOR.close();
        CHUNK_INGESTS.clear();
        RECORDING_TASKS.clear();

        List<LodRequest> pending;
        synchronized (LOD_LOCK) {
            pending = List.copyOf(IN_FLIGHT.values());
            IN_FLIGHT.clear();
            LOD_CACHE.clear();
        }

        for (LodRequest request : pending) {
            request.future.cancel(false);
        }
    }

    public static int getPendingTaskCount() {
        PriorityThreadPoolExecutor executor = EXECUTOR;
        return executor == null ? 0 : executor.getActiveCount() + executor.getQueue().size();
    }

    public static void clearCache() {
        pruneCache(key -> false);
    }

    public static void pruneCache(Predicate<CacheKey> keep) {
        List<LodRequest> cancelled = new ArrayList<>();
        synchronized (LOD_LOCK) {
            LOD_CACHE.removeIf(key -> !keep.test(key));
            IN_FLIGHT.entrySet().removeIf(entry -> {
                if (keep.test(entry.getKey())) return false;
                cancelled.add(entry.getValue());
                return true;
            });
        }
        cancelled.forEach(request -> request.future.cancel(false));
    }

    public static long getColorCacheBytes() {
        return LOD_CACHE.values().stream().mapToLong(data -> data.data() == null ? 0 : data.data().capacity()).sum();
    }

    public static long getRecordingQueueBytes() {
        return CHUNK_INGESTS.values().stream().mapToLong(ChunkSnapshot::estimatedBytes).sum();
    }

    public static void processChunk(ChunkAccess chunk, Level level) {
        processChunk(chunk, level, 0);
    }

    public static void processChunk(LevelChunk chunk) {
        processChunk(chunk, chunk.getLevel(), Integer.MAX_VALUE);
    }

    private static void processChunk(ChunkAccess chunk, Level level, int priority) {
        if (EXECUTOR == null || EXECUTOR.isShutdown() || !DatabaseManager.isInitialized()) return;

        CacheKey key = new CacheKey(chunk.getPos().x(), chunk.getPos().z(), level.dimension().identifier().toString(), 0);

        // Copy palettes on the owning thread.
        List<PalettedContainer<BlockState>> sections = Arrays.stream(chunk.getSections())
                .map(section -> section.hasOnlyAir() ? null : section.getStates().copy()).toList();

        processChunk(key, sections, priority);
    }

    public static void processChunk(CacheKey key, List<PalettedContainer<BlockState>> sections, int priority) {
        long bytes = 128L + sections.size() * 8L + sections.stream().filter(Objects::nonNull).mapToLong(section -> section.getSerializedSize() + 128L).sum();
        CHUNK_INGESTS.put(key, new ChunkSnapshot(sections, bytes));
        queueRecording(key, priority);
    }

    private static void queueRecording(CacheKey key, int priority) {
        if (!RECORDING_TASKS.add(key)) return;

        ThreadPoolUtil.failOnError(EXECUTOR.submit(priority, () -> {
            boolean completed = false;
            try {
                ChunkSnapshot snapshot;
                while ((snapshot = CHUNK_INGESTS.get(key)) != null) {
                    processChunkSync(key, snapshot.sections(), snapshot);
                    if (CHUNK_INGESTS.remove(key, snapshot)) break;
                }
                completed = true;
            } finally {
                RECORDING_TASKS.remove(key);
                if (completed && CHUNK_INGESTS.containsKey(key) && !EXECUTOR.isShutdown()) {
                    queueRecording(key, priority);
                }
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                RECORDING_TASKS.remove(key);
                // TODO: Preserve failed LOD-0 recordings so unloaded chunks are not permanently missing from the map.
                CHUNK_INGESTS.remove(key);
            }
        }));
    }

    private static int getBlockIndex(int height, int x, int y, int z) {
        return (x * height * 16) + (y * 16) + z;
    }

    // Flowers are worth keeping, so their color is inlined into the surface below instead of a voxel.
    private static boolean isFlower(BlockState state) {
        return state.is(BlockTags.FLOWERS);
    }

    // Ground foliage just adds uniform green noise, so it is dropped entirely.
    private static boolean isGroundFoliage(BlockState state) {
        return state.is(BlockTags.REPLACEABLE) && state.getBlock() instanceof VegetationBlock;
    }

    private static void processChunkSync(CacheKey key, List<PalettedContainer<BlockState>> sections, ChunkSnapshot revision) {
        if (!DatabaseManager.isInitialized() || CHUNK_INGESTS.get(key) != revision) {
            return;
        }

        long chunkHash = ChunkHash.of(sections);
        try {
            Long storedHash = DatabaseManager.getInstance().getChunkHash(key.chunkX(), key.chunkZ(), key.dimension(), 0);
            if (storedHash != null && storedHash == chunkHash) {
                return;
            }
        } catch (SQLException e) {
            throw new CompletionException(e);
        }

        // Full vertical chunk: 16 x height x 16 bytes for LOD 0
        int height = sections.size() * 16;
        byte[] chunkData = sections.stream().allMatch(Objects::isNull) ? null : new byte[16 * height * 16];

        // Process all blocks in the chunk column
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            PalettedContainer<BlockState> section = sections.get(sectionIndex);
            if (section == null) {
                continue;
            }

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.get(x, y, z);
                        int ay = (sectionIndex * 16) + y;
                        int color = state.getBlock().defaultMapColor().id;

                        if (isFlower(state)) {
                            // The voxel below is already recorded, including across section boundaries.
                            if (ay > 0) {
                                int belowIndex = getBlockIndex(height, x, ay - 1, z);
                                if (chunkData[belowIndex] != 0) {
                                    chunkData[belowIndex] = (byte) color;
                                }
                            }
                            color = 0;
                        } else if (isGroundFoliage(state)) {
                            color = 0;
                        }

                        int blockIndex = getBlockIndex(height, x, ay, z);
                        chunkData[blockIndex] = (byte) color;
                    }
                }
            }
        }

        int minSurface = chunkData == null ? 0 : findMinSurface(chunkData);
        byte[] compressed = chunkData == null ? null : CompressionUtil.compress(chunkData);

        synchronized (LOD_LOCK) {
            // An older queued snapshot must not overwrite a newer full or partial update.
            if (CHUNK_INGESTS.get(key) == revision) {
                upsertCompressedChunkData(key, compressed, minSurface, chunkHash, null, 0);
            }
        }
    }

    // Lowest surface height in the chunk, 0 when no column has one
    private static int findMinSurface(byte[] data) {
        int height = data.length / 256;
        int min = Integer.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = 0;
                for (int y = height - 1; y > 0; y--) {
                    if (data[x * height * 16 + y * 16 + z] != 0) {
                        top = y;
                        break;
                    }
                }
                min = Math.min(min, top);
            }
        }
        return min;
    }

    private static void upsertCompressedChunkData(CacheKey key, byte[] data, int minSurface, long chunkHash, LodRequest request, long revision) {
        synchronized (LOD_LOCK) {
            // A source may have changed while the LOD's children were being loaded or downsampled.
            if (request != null && (IN_FLIGHT.get(key) != request || request.revision != revision)) {
                return;
            }
            try {
                DatabaseManager.getInstance().upsertCompressedChunk(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), data, minSurface, chunkHash);
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
                invalidationListener.accept(key);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }
    }

    private static CompletableFuture<LodChunkData> loadLodAsync(CacheKey key, LodRequest request, long revision) {
        return EXECUTOR.submit(key.lod(), key, () -> loadStoredChunk(key, request, revision)).thenCompose(stored -> {
            if (request.future.isCancelled()) {
                return CompletableFuture.failedFuture(new CancellationException());
            }
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

    private static ChunkLodDatabase.StoredChunk loadStoredChunk(CacheKey key, LodRequest request, long revision) {
        if (!DatabaseManager.isInitialized()) {
            return new ChunkLodDatabase.StoredChunk(false, null, false, 0);
        }
        try {
            ChunkLodDatabase database = DatabaseManager.getInstance();
            ChunkLodDatabase.StoredChunk stored = database.loadChunk(key.chunkX(), key.chunkZ(), key.dimension(), key.lod());
            if (!stored.exists() && key.lod() > 0 && !database.hasRecordedChunks(key.chunkX(), key.chunkZ(), key.dimension(), key.lod())) {
                upsertCompressedChunkData(key, null, 0, 0L, request, revision);
                return new ChunkLodDatabase.StoredChunk(true, null, false, 0);
            }
            return stored;
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private static CompletableFuture<LodChunkData> generateLodAsync(CacheKey key, LodRequest request, long revision) {
        int baseX = key.chunkX() * 2;
        int baseZ = key.chunkZ() * 2;
        List<CompletableFuture<LodChunkData>> sources;
        synchronized (LOD_LOCK) {
            if (IN_FLIGHT.get(key) != request) {
                return CompletableFuture.failedFuture(new CancellationException());
            }
            sources = List.of(
                    getLodChunkDataAsync(baseX, baseZ, key.dimension(), key.lod() - 1),
                    getLodChunkDataAsync(baseX + 1, baseZ, key.dimension(), key.lod() - 1),
                    getLodChunkDataAsync(baseX, baseZ + 1, key.dimension(), key.lod() - 1),
                    getLodChunkDataAsync(baseX + 1, baseZ + 1, key.dimension(), key.lod() - 1)
            );
        }
        return CompletableFuture.allOf(sources.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            if (sources.stream().map(CompletableFuture::join).allMatch(LodChunkData::empty)) {
                upsertCompressedChunkData(key, null, 0, 0L, request, revision);
                return CompletableFuture.completedFuture(new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), null, 0));
            }
            return EXECUTOR.submit(key.lod(), key, () -> {
                LodChunkData[][] data = {{sources.get(0).join(), sources.get(2).join()}, {sources.get(1).join(), sources.get(3).join()}};
                byte[] result = generateLod(data);
                int minSurface = findMinSurface(result);
                byte[] compressed = CompressionUtil.compress(result);
                upsertCompressedChunkData(key, compressed, minSurface, 0L, request, revision);
                return new LodChunkData(key.chunkX(), key.chunkZ(), key.dimension(), key.lod(), PooledBytes.wrap(result), minSurface);
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
        int outHeight = Math.max(1, (sourceHeight + 1) / 2);
        byte[] result = new byte[16 * outHeight * 16];

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
                        // Sample coverage horizontally, but retain thin surface layers vertically.
                        long position = ChunkPos.pack(src.chunkX() * 8 + x, src.chunkZ() * 8 + z);
                        int sample = (int) HashCommon.murmurHash3(position + src.lodLevel()) & 3;
                        int sx = x * 2 + (sample >> 1);
                        int sz = z * 2 + (sample & 1);

                        for (int y = 0; y < outHeight; y++) {
                            byte upper = src.getBlock(sx, y * 2 + 1, sz);
                            byte color = upper != 0 ? upper : src.getBlock(sx, y * 2, sz);
                            result[getBlockIndex(outHeight, cx * 8 + x, y, cz * 8 + z)] = color;
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
     * @return a future containing chunk data; missing chunks have empty data
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
        ThreadPoolUtil.failOnError(loadLodAsync(key, request, revision).whenComplete((data, error) -> {
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
        }));
    }

    public static void discardQueuedTasksOutside(Set<CacheKey> visibleKeys) {
        if (EXECUTOR == null) return;
        EXECUTOR.discardQueuedTasksOutside(tag -> tag instanceof CacheKey key && isCoveredByViewport(key, visibleKeys));
    }

    public static void clearQueuedViewportTasks() {
        if (EXECUTOR == null) return;
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
        @Override
        public int hashCode() {
            long coordinates = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
            int hash = HashCommon.long2int(HashCommon.mix(coordinates));
            return 31 * (31 * hash + Objects.hashCode(dimension)) + lod;
        }
    }
}
