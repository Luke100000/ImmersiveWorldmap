package net.conczin.immersive_worldmap.lod;

import com.mojang.blaze3d.vertex.*;
import net.conczin.immersive_worldmap.util.ColorManager;
import net.conczin.immersive_worldmap.util.TesselatorPool;

import java.util.concurrent.CompletableFuture;

/**
 * Renders LOD chunk data as a mesh.
 * Uses greedy meshing to build optimized geometry from voxel data.
 */
public class LodChunkRenderer {
    /**
     * Asynchronously builds a mesh from chunk coordinates.
     * Fetches LOD data in the background thread to avoid IO spikes.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param dimension dimension identifier
     * @param lod       LOD level
     * @return CompletableFuture that will contain the built mesh data, or null if no data exists
     */
    public static CompletableFuture<MeshData> buildMesh(int chunkX, int chunkZ, String dimension, int lod) {
        return CompletableFuture.supplyAsync(() -> buildMeshSync(chunkX, chunkZ, dimension, lod), ChunkLodProcessor.EXECUTOR);
    }

    public static MeshData buildMeshSync(int chunkX, int chunkZ, String dimension, int lod) {
        LodChunkData lodData = ChunkLodProcessor.getLodChunkData(chunkX, chunkZ, dimension, lod);
        if (lodData.empty()) {
            return null;
        }

        LodChunkData north = ChunkLodProcessor.getLodChunkData(chunkX, chunkZ - 1, dimension, lod);
        LodChunkData south = ChunkLodProcessor.getLodChunkData(chunkX, chunkZ + 1, dimension, lod);
        LodChunkData west = ChunkLodProcessor.getLodChunkData(chunkX - 1, chunkZ, dimension, lod);
        LodChunkData east = ChunkLodProcessor.getLodChunkData(chunkX + 1, chunkZ, dimension, lod);

        Tesselator tesselator = TesselatorPool.acquire();
        try {
            BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_NORMAL);

            int height = lodData.getHeight();

            // Different shading for each face direction
            float topBrightness = 1.0F;
            float bottomBrightness = 0.5F;
            float sideBrightness = 0.8F;
            float sideEWBrightness = 0.72F; // 0.8 * 0.9

            // Iterate through all blocks and render visible faces
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < 16; z++) {
                        byte blockColorId = lodData.getBlock(x, y, z);
                        if (blockColorId == 0) {
                            continue; // Skip air blocks (MapColor.NONE has id 0)
                        }

                        // Get RGBA color from ColorManager
                        int[] color = ColorManager.byteToRGBA(blockColorId);
                        int r = color[0];
                        int g = color[1];
                        int b = color[2];
                        int a = color[3];

                        // Pre-calculate brightness-adjusted colors for each face direction
                        int rTop = (int) (r * topBrightness);
                        int gTop = (int) (g * topBrightness);
                        int bTop = (int) (b * topBrightness);

                        int rBottom = (int) (r * bottomBrightness);
                        int gBottom = (int) (g * bottomBrightness);
                        int bBottom = (int) (b * bottomBrightness);

                        int rSide = (int) (r * sideBrightness);
                        int gSide = (int) (g * sideBrightness);
                        int bSide = (int) (b * sideBrightness);

                        int rSideEW = (int) (r * sideEWBrightness);
                        int gSideEW = (int) (g * sideEWBrightness);
                        int bSideEW = (int) (b * sideEWBrightness);

                        float x0 = x;
                        float y0 = y;
                        float z0 = z;
                        float x1 = x0 + 1.0F;
                        float y1 = y0 + 1.0F;
                        float z1 = z0 + 1.0F;

                        // Top face (Y+)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x, y + 1, z)) {
                            builder.addVertex(x0, y1, z1).setColor(rTop, gTop, bTop, a).setNormal(0.0F, 1.0F, 0.0F);
                            builder.addVertex(x1, y1, z1).setColor(rTop, gTop, bTop, a).setNormal(0.0F, 1.0F, 0.0F);
                            builder.addVertex(x1, y1, z0).setColor(rTop, gTop, bTop, a).setNormal(0.0F, 1.0F, 0.0F);
                            builder.addVertex(x0, y1, z0).setColor(rTop, gTop, bTop, a).setNormal(0.0F, 1.0F, 0.0F);
                        }

                        // Bottom face (Y-)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x, y - 1, z)) {
                            builder.addVertex(x0, y0, z0).setColor(rBottom, gBottom, bBottom, a).setNormal(0.0F, -1.0F, 0.0F);
                            builder.addVertex(x1, y0, z0).setColor(rBottom, gBottom, bBottom, a).setNormal(0.0F, -1.0F, 0.0F);
                            builder.addVertex(x1, y0, z1).setColor(rBottom, gBottom, bBottom, a).setNormal(0.0F, -1.0F, 0.0F);
                            builder.addVertex(x0, y0, z1).setColor(rBottom, gBottom, bBottom, a).setNormal(0.0F, -1.0F, 0.0F);
                        }

                        // North face (Z-)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x, y, z - 1)) {
                            builder.addVertex(x0, y1, z0).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, -1.0F);
                            builder.addVertex(x1, y1, z0).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, -1.0F);
                            builder.addVertex(x1, y0, z0).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, -1.0F);
                            builder.addVertex(x0, y0, z0).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, -1.0F);
                        }

                        // South face (Z+)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x, y, z + 1)) {
                            builder.addVertex(x0, y0, z1).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, 1.0F);
                            builder.addVertex(x1, y0, z1).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, 1.0F);
                            builder.addVertex(x1, y1, z1).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, 1.0F);
                            builder.addVertex(x0, y1, z1).setColor(rSide, gSide, bSide, a).setNormal(0.0F, 0.0F, 1.0F);
                        }

                        // West face (X-)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x - 1, y, z)) {
                            builder.addVertex(x0, y0, z0).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(-1.0F, 0.0F, 0.0F);
                            builder.addVertex(x0, y0, z1).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(-1.0F, 0.0F, 0.0F);
                            builder.addVertex(x0, y1, z1).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(-1.0F, 0.0F, 0.0F);
                            builder.addVertex(x0, y1, z0).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(-1.0F, 0.0F, 0.0F);
                        }

                        // East face (X+)
                        if (isAirWithNeighbors(lodData, north, south, west, east, x + 1, y, z)) {
                            builder.addVertex(x1, y1, z0).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(1.0F, 0.0F, 0.0F);
                            builder.addVertex(x1, y1, z1).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(1.0F, 0.0F, 0.0F);
                            builder.addVertex(x1, y0, z1).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(1.0F, 0.0F, 0.0F);
                            builder.addVertex(x1, y0, z0).setColor(rSideEW, gSideEW, bSideEW, a).setNormal(1.0F, 0.0F, 0.0F);
                        }
                    }
                }
            }

            return builder.buildOrThrow();
        } finally {
            TesselatorPool.release(tesselator);
        }
    }

    private static boolean isAirWithNeighbors(
            LodChunkData center,
            LodChunkData north,
            LodChunkData south,
            LodChunkData west,
            LodChunkData east,
            int x,
            int y,
            int z
    ) {
        if (y < 0) {
            return true;
        }

        if (x < 0) {
            return isAir(west, x + 16, y, z);
        }
        if (x > 15) {
            return isAir(east, x - 16, y, z);
        }
        if (z < 0) {
            return isAir(north, x, y, z + 16);
        }
        if (z > 15) {
            return isAir(south, x, y, z - 16);
        }

        if (y >= center.getHeight()) {
            return true;
        }
        return center.getBlock(x, y, z) == 0;
    }

    private static boolean isAir(LodChunkData chunk, int x, int y, int z) {
        return chunk.getBlock(x, y, z) == 0;
    }
}
