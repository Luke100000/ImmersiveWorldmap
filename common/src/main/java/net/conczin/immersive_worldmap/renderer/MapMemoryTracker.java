package net.conczin.immersive_worldmap.renderer;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;

import java.util.Locale;

public final class MapMemoryTracker {
    private static final long MIN_CACHE_BYTES = 128L * 1024 * 1024;

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
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        long estimated = colorBytes + cpuBytes + pageBytes + recordingBytes;

        LodChunkMeshManager.INSTANCE.trim(Math.max(MIN_CACHE_BYTES, (available - estimated) / 2));
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
