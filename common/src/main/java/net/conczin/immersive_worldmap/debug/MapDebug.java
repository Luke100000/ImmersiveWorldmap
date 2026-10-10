package net.conczin.immersive_worldmap.debug;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.database.DatabaseManager;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.conczin.immersive_worldmap.renderer.LodChunkMeshManager;
import net.conczin.immersive_worldmap.renderer.LodChunkVisibilitySelector;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;

public final class MapDebug {
    private static volatile boolean reprocessing;

    public static void clearDimensionLods(String dimension) {
        DatabaseManager.getInstance().clearDimension(dimension);
        clearMapCaches();
    }

    public static void clearGeneratedLods(String dimension) {
        DatabaseManager.getInstance().clearGeneratedLods(dimension);
        clearMapCaches();
    }

    private static void clearMapCaches() {
        ChunkLodProcessor.clearCache();
        LodChunkVisibilitySelector.reset();
        LodChunkMeshManager.INSTANCE.clear();
    }

    public static void reprocessGeneratedChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.level == null || !ServerChunkRecorder.isRunning() || reprocessing) return;

        var level = server.getLevel(minecraft.level.dimension());
        var executor = ChunkLodProcessor.EXECUTOR;
        if (level == null || executor == null || executor.isShutdown() || !DatabaseManager.isInitialized()) return;

        reprocessing = true;
        var database = DatabaseManager.getInstance();
        String dimension = level.dimension().location().toString();
        Path regionFolder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
        Thread worker = new Thread(() -> {
            ImmersiveWorldmap.LOGGER.info("Started chunk reprocessing!");

            int queued = 0;
            try {
                database.invalidateChunkHashes(dimension);
                var codec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                        PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
                if (!Files.isDirectory(regionFolder)) return;

                try (var files = Files.list(regionFolder)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList()) {
                        ImmersiveWorldmap.LOGGER.info("Processing region {}...", file.getFileName());

                        String[] name = file.getFileName().toString().split("\\.");
                        int regionX = Integer.parseInt(name[1]);
                        int regionZ = Integer.parseInt(name[2]);

                        // Read only saved palettes, without generating chunks or loading them into the world.
                        try (DataInputStream header = new DataInputStream(Files.newInputStream(file))) {
                            for (int index = 0; index < 1024; index++) {
                                if (server.isStopped() || executor.isShutdown() || ChunkLodProcessor.EXECUTOR != executor)
                                    return;
                                if (header.readInt() == 0) continue;

                                var pos = new ChunkPos(regionX * 32 + index % 32, regionZ * 32 + index / 32);
                                CompoundTag tag = level.getChunkSource().chunkMap.read(pos).join().orElse(null);
                                if (tag == null || ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL)
                                    continue;

                                var sections = new ArrayList<PalettedContainer<BlockState>>(Collections.nCopies(level.getSectionsCount(), null));
                                var savedSections = tag.getList("sections", 10);
                                for (int i = 0; i < savedSections.size(); i++) {
                                    CompoundTag section = savedSections.getCompound(i);
                                    int sectionIndex = level.getSectionIndexFromSectionY(section.getByte("Y"));
                                    if (sectionIndex < 0 || sectionIndex >= sections.size() || !section.contains("block_states", 10))
                                        continue;

                                    sections.set(sectionIndex, codec.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow());
                                }

                                if (server.isStopped() || executor.isShutdown() || ChunkLodProcessor.EXECUTOR != executor) {
                                    return;
                                }
                                ChunkLodProcessor.processChunk(new ChunkLodProcessor.CacheKey(pos.x, pos.z, dimension, 0), sections, Integer.MAX_VALUE);
                                queued++;
                            }
                        } catch (EOFException e) {
                            ImmersiveWorldmap.LOGGER.error("Failed to reprocess region {}", file.getFileName(), e);
                        }
                    }
                }
                ImmersiveWorldmap.LOGGER.info("Queued {} saved chunks for map reprocessing in {}", queued, dimension);
            } catch (IOException | SQLException | RuntimeException error) {
                ImmersiveWorldmap.LOGGER.error("Failed to reprocess saved chunks in {} after queueing {} chunks", dimension, queued, error);
            } finally {
                reprocessing = false;
            }
        }, "ImmersiveWorldmap-DebugReprocess");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }
}
