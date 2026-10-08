package net.conczin.immersive_worldmap.renderer;

import com.sun.management.GarbageCollectorMXBean;
import com.sun.management.GcInfo;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class MapMemoryTracker {
    private static final long MIN_CACHE_BYTES = 128L * 1024 * 1024;

    private static final List<java.lang.management.GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final Set<String> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP).map(MemoryPoolMXBean::getName).collect(Collectors.toSet());
    private static long lastGcEnd = -1;
    private static long usedAfterGc = -1;

    private static long colorBytes;
    private static long cpuBytes;
    private static long pageBytes;
    private static long recordingBytes;

    private MapMemoryTracker() {
    }

    public static void update() {
        colorBytes = ChunkLodProcessor.getColorCacheBytes();
        cpuBytes = LodChunkMeshManager.INSTANCE.getEstimatedBytes();
        pageBytes = LodChunkPageManager.INSTANCE.getEstimatedRamBytes();
        recordingBytes = ChunkLodProcessor.getRecordingQueueBytes();

        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - heapUsedAfterGc(runtime);
        long estimated = colorBytes + cpuBytes + pageBytes + recordingBytes;

        LodChunkMeshManager.INSTANCE.trim(Math.max(MIN_CACHE_BYTES, (available - estimated) / 2));
    }

    private static long heapUsedAfterGc(Runtime runtime) {
        for (var collector : COLLECTORS) {
            if (!(collector instanceof GarbageCollectorMXBean bean)) continue;

            GcInfo info = bean.getLastGcInfo();
            if (info == null || info.getEndTime() <= lastGcEnd) continue;

            lastGcEnd = info.getEndTime();
            usedAfterGc = info.getMemoryUsageAfterGc().entrySet().stream()
                    .filter(entry -> HEAP_POOLS.contains(entry.getKey()))
                    .mapToLong(entry -> entry.getValue().getUsed()).sum();
        }
        return usedAfterGc >= 0 ? usedAfterGc : runtime.totalMemory() - runtime.freeMemory();
    }

    public static String colorCacheSize() {
        return formatBytes(colorBytes);
    }

    public static String cpuCacheSize() {
        return formatBytes(cpuBytes);
    }

    public static String cpuCacheBudget() {
        return formatBytes(LodChunkMeshManager.INSTANCE.getByteBudget());
    }

    public static String pageRamSize() {
        return formatBytes(pageBytes);
    }

    public static String pageGpuSize() {
        return formatBytes(LodChunkPageManager.INSTANCE.getGpuBytes());
    }

    public static String recordingQueueSize() {
        return formatBytes(recordingBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0fKiB", bytes / 1024.0);
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fGiB", bytes / (1024.0 * 1024 * 1024));
        return String.format(Locale.ROOT, "%.1fMiB", bytes / (1024.0 * 1024));
    }
}
