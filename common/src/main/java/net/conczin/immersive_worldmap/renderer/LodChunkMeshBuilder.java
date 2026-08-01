package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.vertex.*;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.ColorManager;
import net.conczin.immersive_worldmap.util.TesselatorPool;

import java.util.concurrent.CompletableFuture;

/**
 * Renders LOD chunk data as a mesh.
 * Uses greedy meshing to build optimized geometry from voxel data.
 */
public class LodChunkMeshBuilder {
    /**
     * Builds a mesh from chunk coordinates.
     * Fetches LOD data in the background thread to avoid IO spikes.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param dimension dimension identifier
     * @param lod       LOD level
     * @return CompletableFuture that will contain the built mesh data, or null if no data exists
     */
    @SuppressWarnings("DuplicatedCode")
    public static CompletableFuture<MeshData> buildMeshAsync(int chunkX, int chunkZ, String dimension, int lod) {
        CompletableFuture<LodChunkData> center = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ, dimension, lod);
        CompletableFuture<LodChunkData> north = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ - 1, dimension, lod);
        CompletableFuture<LodChunkData> south = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ + 1, dimension, lod);
        CompletableFuture<LodChunkData> west = ChunkLodProcessor.getLodChunkDataAsync(chunkX - 1, chunkZ, dimension, lod);
        CompletableFuture<LodChunkData> east = ChunkLodProcessor.getLodChunkDataAsync(chunkX + 1, chunkZ, dimension, lod);
        return CompletableFuture.allOf(center, north, south, west, east).thenCompose(ignored ->
                ChunkLodProcessor.EXECUTOR.submit(lod, () -> buildMesh(center.join(), north.join(), south.join(), west.join(), east.join())));
    }

    @SuppressWarnings("DuplicatedCode")
    private static MeshData buildMesh(LodChunkData center, LodChunkData north, LodChunkData south, LodChunkData west, LodChunkData east) {
        if (center.empty()) return null;

        Tesselator tesselator = TesselatorPool.acquire();
        try {
            BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            int height = center.getHeight();

            // Different shading for each face direction
            float topBrightness = 1.0F;
            float bottomBrightness = 0.5F;
            float sideBrightness = 0.8F;
            float sideEWBrightness = 0.72F; // 0.8 * 0.9

            // Iterate through all blocks and render visible faces
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < 16; z++) {
                        byte blockColorId = center.getBlock(x, y, z);
                        if (blockColorId == 0) {
                            continue; // Skip air blocks (MapColor.NONE has id 0)
                        }

                        // Get RGBA color from ColorManager
                        int[] color = ColorManager.byteToRGBA(blockColorId);
                        int b = color[0];
                        int g = color[1];
                        int r = color[2];

                        // Add noise
                        int noise = (int) ((Math.random() - 0.5) * 16);
                        r = Math.clamp(r + noise, 0, 255);
                        g = Math.clamp(g + noise, 0, 255);
                        b = Math.clamp(b + noise, 0, 255);

                        // Pre-calculate brightness-adjusted colors for each face direction
                        float rTop = r * topBrightness;
                        float gTop = g * topBrightness;
                        float bTop = b * topBrightness;

                        float rBottom = r * bottomBrightness;
                        float gBottom = g * bottomBrightness;
                        float bBottom = b * bottomBrightness;

                        float rSide = r * sideBrightness;
                        float gSide = g * sideBrightness;
                        float bSide = b * sideBrightness;

                        float rSideEW = r * sideEWBrightness;
                        float gSideEW = g * sideEWBrightness;
                        float bSideEW = b * sideEWBrightness;

                        int x1 = x + 1;
                        int y1 = y + 1;
                        int z1 = z + 1;

                        // Top face (Y+)
                        if (isAirWithNeighbors(center, north, south, west, east, x, y + 1, z)) {
                            float ao00 = vertexAO(center, north, south, west, east, x - 1, y + 1, z, x, y + 1, z - 1, x - 1, y + 1, z - 1);
                            float ao10 = vertexAO(center, north, south, west, east, x + 1, y + 1, z, x, y + 1, z - 1, x + 1, y + 1, z - 1);
                            float ao11 = vertexAO(center, north, south, west, east, x + 1, y + 1, z, x, y + 1, z + 1, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(center, north, south, west, east, x - 1, y + 1, z, x, y + 1, z + 1, x - 1, y + 1, z + 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x, y1, z, rTop, gTop, bTop, ao00);
                                v(builder, x, y1, z1, rTop, gTop, bTop, ao01);
                                v(builder, x1, y1, z1, rTop, gTop, bTop, ao11);
                                v(builder, x1, y1, z, rTop, gTop, bTop, ao10);
                            } else {
                                v(builder, x, y1, z1, rTop, gTop, bTop, ao01);
                                v(builder, x1, y1, z1, rTop, gTop, bTop, ao11);
                                v(builder, x1, y1, z, rTop, gTop, bTop, ao10);
                                v(builder, x, y1, z, rTop, gTop, bTop, ao00);
                            }
                        }

                        // Bottom face (Y-)
                        //noinspection PointlessBooleanExpression
                        if (isAirWithNeighbors(center, north, south, west, east, x, y - 1, z) && false) {
                            float ao00 = vertexAO(center, north, south, west, east, x - 1, y - 1, z, x, y - 1, z - 1, x - 1, y - 1, z - 1);
                            float ao10 = vertexAO(center, north, south, west, east, x + 1, y - 1, z, x, y - 1, z - 1, x + 1, y - 1, z - 1);
                            float ao11 = vertexAO(center, north, south, west, east, x + 1, y - 1, z, x, y - 1, z + 1, x + 1, y - 1, z + 1);
                            float ao01 = vertexAO(center, north, south, west, east, x - 1, y - 1, z, x, y - 1, z + 1, x - 1, y - 1, z + 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x, y, z, rBottom, gBottom, bBottom, ao00);
                                v(builder, x, y, z1, rBottom, gBottom, bBottom, ao01);
                                v(builder, x1, y, z1, rBottom, gBottom, bBottom, ao11);
                                v(builder, x1, y, z, rBottom, gBottom, bBottom, ao10);
                            } else {
                                v(builder, x, y, z, rBottom, gBottom, bBottom, ao00);
                                v(builder, x1, y, z, rBottom, gBottom, bBottom, ao10);
                                v(builder, x1, y, z1, rBottom, gBottom, bBottom, ao11);
                                v(builder, x, y, z1, rBottom, gBottom, bBottom, ao01);
                            }
                        }

                        // North face (Z-)
                        if (isAirWithNeighbors(center, north, south, west, east, x, y, z - 1)) {
                            float ao00 = vertexAO(center, north, south, west, east, x - 1, y, z - 1, x, y - 1, z - 1, x - 1, y - 1, z - 1);
                            float ao10 = vertexAO(center, north, south, west, east, x + 1, y, z - 1, x, y - 1, z - 1, x + 1, y - 1, z - 1);
                            float ao11 = vertexAO(center, north, south, west, east, x + 1, y, z - 1, x, y + 1, z - 1, x + 1, y + 1, z - 1);
                            float ao01 = vertexAO(center, north, south, west, east, x - 1, y, z - 1, x, y + 1, z - 1, x - 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x, y, z, rSide, gSide, bSide, ao00);
                                v(builder, x, y1, z, rSide, gSide, bSide, ao01);
                                v(builder, x1, y1, z, rSide, gSide, bSide, ao11);
                                v(builder, x1, y, z, rSide, gSide, bSide, ao10);
                            } else {
                                v(builder, x, y1, z, rSide, gSide, bSide, ao01);
                                v(builder, x1, y1, z, rSide, gSide, bSide, ao11);
                                v(builder, x1, y, z, rSide, gSide, bSide, ao10);
                                v(builder, x, y, z, rSide, gSide, bSide, ao00);
                            }
                        }

                        // South face (Z+)
                        if (isAirWithNeighbors(center, north, south, west, east, x, y, z + 1)) {
                            float ao00 = vertexAO(center, north, south, west, east, x - 1, y, z + 1, x, y - 1, z + 1, x - 1, y - 1, z + 1);
                            float ao10 = vertexAO(center, north, south, west, east, x + 1, y, z + 1, x, y - 1, z + 1, x + 1, y - 1, z + 1);
                            float ao11 = vertexAO(center, north, south, west, east, x + 1, y, z + 1, x, y + 1, z + 1, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(center, north, south, west, east, x - 1, y, z + 1, x, y + 1, z + 1, x - 1, y + 1, z + 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x, y, z1, rSide, gSide, bSide, ao00);
                                v(builder, x1, y, z1, rSide, gSide, bSide, ao10);
                                v(builder, x1, y1, z1, rSide, gSide, bSide, ao11);
                                v(builder, x, y1, z1, rSide, gSide, bSide, ao01);
                            } else {
                                v(builder, x1, y, z1, rSide, gSide, bSide, ao10);
                                v(builder, x1, y1, z1, rSide, gSide, bSide, ao11);
                                v(builder, x, y1, z1, rSide, gSide, bSide, ao01);
                                v(builder, x, y, z1, rSide, gSide, bSide, ao00);
                            }
                        }

                        // West face (X-)
                        if (isAirWithNeighbors(center, north, south, west, east, x - 1, y, z)) {
                            float ao00 = vertexAO(center, north, south, west, east, x - 1, y, z - 1, x - 1, y - 1, z, x - 1, y - 1, z - 1);
                            float ao10 = vertexAO(center, north, south, west, east, x - 1, y, z + 1, x - 1, y - 1, z, x - 1, y - 1, z + 1);
                            float ao11 = vertexAO(center, north, south, west, east, x - 1, y, z + 1, x - 1, y + 1, z, x - 1, y + 1, z + 1);
                            float ao01 = vertexAO(center, north, south, west, east, x - 1, y, z - 1, x - 1, y + 1, z, x - 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(builder, x, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(builder, x, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(builder, x, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                            } else {
                                v(builder, x, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(builder, x, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(builder, x, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(builder, x, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                            }
                        }

                        // East face (X+)
                        if (isAirWithNeighbors(center, north, south, west, east, x + 1, y, z)) {
                            float ao00 = vertexAO(center, north, south, west, east, x + 1, y, z - 1, x + 1, y - 1, z, x + 1, y - 1, z - 1);
                            float ao10 = vertexAO(center, north, south, west, east, x + 1, y, z + 1, x + 1, y - 1, z, x + 1, y - 1, z + 1);
                            float ao11 = vertexAO(center, north, south, west, east, x + 1, y, z + 1, x + 1, y + 1, z, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(center, north, south, west, east, x + 1, y, z - 1, x + 1, y + 1, z, x + 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(builder, x1, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(builder, x1, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(builder, x1, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(builder, x1, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                            } else {
                                v(builder, x1, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(builder, x1, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(builder, x1, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(builder, x1, y, z, rSideEW, gSideEW, bSideEW, ao00);
                            }
                        }
                    }
                }
            }

            // Anti-empty-mesh
            // TODO
            v(builder, 0, 0, 0, 1, 1, 1, 1);
            v(builder, 0, 0, 0, 1, 1, 1, 1);
            v(builder, 0, 0, 0, 1, 1, 1, 1);
            v(builder, 0, 0, 0, 1, 1, 1, 1);

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

    private static void v(BufferBuilder b, float x, float y, float z, float r, float g, float col, float ao) {
        b.addVertex(x, y, z).setColor((int) (r * ao), (int) (g * ao), (int) (col * ao), 255);
    }

    private static boolean isAir(LodChunkData chunk, int x, int y, int z) {
        return chunk.getBlock(x, y, z) == 0;
    }

    private static float vertexAO(
            LodChunkData center,
            LodChunkData north, LodChunkData south, LodChunkData west, LodChunkData east,
            int sx, int sy, int sz,
            int cx, int cy, int cz,
            int ex, int ey, int ez
    ) {
        boolean side1 = !isAirWithNeighbors(center, north, south, west, east, sx, sy, sz);
        boolean side2 = !isAirWithNeighbors(center, north, south, west, east, cx, cy, cz);
        boolean corner = !isAirWithNeighbors(center, north, south, west, east, ex, ey, ez);
        if (side1 && side2) return 0.5F;
        return (3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0))) / 6.0f + 0.5f;
    }
}
