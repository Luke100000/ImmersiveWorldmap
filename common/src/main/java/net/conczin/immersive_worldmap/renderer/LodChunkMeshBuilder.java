package net.conczin.immersive_worldmap.renderer;

import com.mojang.blaze3d.vertex.*;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.settings.SharedSettings;
import net.conczin.immersive_worldmap.util.ColorManager;
import net.conczin.immersive_worldmap.util.TesselatorPool;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Renders LOD chunk data as a mesh.
 */
public class LodChunkMeshBuilder {
    private static final byte[] EMPTY = new byte[0];

    // How many blocks of wall are kept above the cave view baseline.
    private static final int CAVE_WALL_HEIGHT = 8;

    // Slack kept below the floor, so a sloping surface is never cut into
    private static final int FLOOR_BUFFER = 8;

    // Brightness multiplier of the top face at the cave view slice.
    private static final float CAVE_TOP_BRIGHTNESS = 0.33F;

    /**
     * Builds a mesh from chunk coordinates.
     * Fetches LOD data in the background thread to avoid IO spikes.
     *
     * @param chunkX    chunk X coordinate
     * @param chunkZ    chunk Z coordinate
     * @param dimension dimension identifier
     * @param lod       LOD level
     * @return CompletableFuture containing the built vertex data
     */
    @SuppressWarnings("DuplicatedCode")
    public static CompletableFuture<byte[]> buildMeshAsync(int chunkX, int chunkZ, String dimension, int lod) {
        CompletableFuture<LodChunkData> center = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ, dimension, lod);
        return center.thenCompose(centerData -> {
            if (centerData.empty()) {
                return CompletableFuture.completedFuture(EMPTY);
            }

            CompletableFuture<LodChunkData> north = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ - 1, dimension, lod);
            CompletableFuture<LodChunkData> south = ChunkLodProcessor.getLodChunkDataAsync(chunkX, chunkZ + 1, dimension, lod);
            CompletableFuture<LodChunkData> west = ChunkLodProcessor.getLodChunkDataAsync(chunkX - 1, chunkZ, dimension, lod);
            CompletableFuture<LodChunkData> east = ChunkLodProcessor.getLodChunkDataAsync(chunkX + 1, chunkZ, dimension, lod);
            CompletableFuture<LodChunkData> northWest = ChunkLodProcessor.getLodChunkDataAsync(chunkX - 1, chunkZ - 1, dimension, lod);
            CompletableFuture<LodChunkData> northEast = ChunkLodProcessor.getLodChunkDataAsync(chunkX + 1, chunkZ - 1, dimension, lod);
            CompletableFuture<LodChunkData> southWest = ChunkLodProcessor.getLodChunkDataAsync(chunkX - 1, chunkZ + 1, dimension, lod);
            CompletableFuture<LodChunkData> southEast = ChunkLodProcessor.getLodChunkDataAsync(chunkX + 1, chunkZ + 1, dimension, lod);
            return CompletableFuture.allOf(north, south, west, east, northWest, northEast, southWest, southEast).thenCompose(ignored ->
                    ChunkLodProcessor.EXECUTOR.submit(lod, new ChunkLodProcessor.CacheKey(chunkX, chunkZ, dimension, lod),
                            () -> buildMesh(new ChunkNeighborhood(centerData, north.join(), south.join(), west.join(), east.join(),
                                    northWest.join(), northEast.join(), southWest.join(), southEast.join()), lod)));
        });
    }

    @SuppressWarnings("DuplicatedCode")
    private static byte[] buildMesh(ChunkNeighborhood neighbors, int lod) {
        LodChunkData center = neighbors.center();
        if (center.empty()) return EMPTY;

        // One LOD block covers 1 << lod world blocks, so the baseline has to be scaled down
        int baseline = Math.clamp(Math.floorDiv(SharedSettings.caveViewBaselineY, 1 << lod), 0, Math.max(0, center.getHeight() - 1));
        neighbors = neighbors.withSlice(SharedSettings.caveView ? new CaveSlice(neighbors, baseline) : null);
        CaveSlice slice = neighbors.slice();

        int floor = slice == null ? Math.max(0, getMinSurrounding(neighbors) - FLOOR_BUFFER) : 0;

        Tesselator tesselator = TesselatorPool.acquire();
        try {
            BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            VertexWriter writer = new VertexWriter(builder,
                    Math.floorMod(center.chunkX(), LodChunkPageManager.PAGE_SIZE) * 16,
                    Math.floorMod(center.chunkZ(), LodChunkPageManager.PAGE_SIZE) * 16);

            int height = center.getHeight();

            // Different shading for each face direction
            float topBrightness = 1.0F;
            float sideBrightness = 0.8F;
            float sideEWBrightness = 0.72F; // 0.8 * 0.9
            boolean hasFaces = false;

            // Iterate through all blocks and render visible faces
            for (int x = 0; x < 16; x++) {
                for (int y = floor; y < height; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (slice != null && y > slice.centerCut(x, z)) {
                            // Cave view: everything above the column's slice is hidden
                            continue;
                        }

                        byte blockColorId = center.getBlock(x, y, z);
                        if (blockColorId == 0) {
                            continue; // Skip air blocks (MapColor.NONE has id 0)
                        }

                        // Get RGBA color from ColorManager
                        int[] color = ColorManager.byteToRGBA(blockColorId);
                        int b = color[0];
                        int g = color[1];
                        int r = color[2];

                        // Add nosie
                        int noise = positionNoise(
                                (center.chunkX() * 16 + x) << lod,
                                y << lod,
                                (center.chunkZ() * 16 + z) << lod
                        );
                        r = Math.clamp(r + noise, 0, 255);
                        g = Math.clamp(g + noise, 0, 255);
                        b = Math.clamp(b + noise, 0, 255);

                        float topMul = (slice != null && y == slice.centerCut(x, z) && center.getBlock(x, y + 1, z) != 0)
                                ? CAVE_TOP_BRIGHTNESS : 1.0F;

                        // Pre-calculate brightness-adjusted colors for each face direction
                        float rTop = r * topBrightness * topMul;
                        float gTop = g * topBrightness * topMul;
                        float bTop = b * topBrightness * topMul;

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
                        if (neighbors.isAir(x, y + 1, z)) {
                            hasFaces = true;
                            float ao00 = vertexAO(neighbors, x - 1, y + 1, z, x, y + 1, z - 1, x - 1, y + 1, z - 1);
                            float ao10 = vertexAO(neighbors, x + 1, y + 1, z, x, y + 1, z - 1, x + 1, y + 1, z - 1);
                            float ao11 = vertexAO(neighbors, x + 1, y + 1, z, x, y + 1, z + 1, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(neighbors, x - 1, y + 1, z, x, y + 1, z + 1, x - 1, y + 1, z + 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(writer, x, y1, z, rTop, gTop, bTop, ao00);
                                v(writer, x, y1, z1, rTop, gTop, bTop, ao01);
                                v(writer, x1, y1, z1, rTop, gTop, bTop, ao11);
                                v(writer, x1, y1, z, rTop, gTop, bTop, ao10);
                            } else {
                                v(writer, x, y1, z1, rTop, gTop, bTop, ao01);
                                v(writer, x1, y1, z1, rTop, gTop, bTop, ao11);
                                v(writer, x1, y1, z, rTop, gTop, bTop, ao10);
                                v(writer, x, y1, z, rTop, gTop, bTop, ao00);
                            }
                        }

                        // North face (Z-)
                        if (neighbors.isAir(x, y, z - 1)) {
                            hasFaces = true;
                            float ao00 = vertexAO(neighbors, x - 1, y, z - 1, x, y - 1, z - 1, x - 1, y - 1, z - 1);
                            float ao10 = vertexAO(neighbors, x + 1, y, z - 1, x, y - 1, z - 1, x + 1, y - 1, z - 1);
                            float ao11 = vertexAO(neighbors, x + 1, y, z - 1, x, y + 1, z - 1, x + 1, y + 1, z - 1);
                            float ao01 = vertexAO(neighbors, x - 1, y, z - 1, x, y + 1, z - 1, x - 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(writer, x, y, z, rSide, gSide, bSide, ao00);
                                v(writer, x, y1, z, rSide, gSide, bSide, ao01);
                                v(writer, x1, y1, z, rSide, gSide, bSide, ao11);
                                v(writer, x1, y, z, rSide, gSide, bSide, ao10);
                            } else {
                                v(writer, x, y1, z, rSide, gSide, bSide, ao01);
                                v(writer, x1, y1, z, rSide, gSide, bSide, ao11);
                                v(writer, x1, y, z, rSide, gSide, bSide, ao10);
                                v(writer, x, y, z, rSide, gSide, bSide, ao00);
                            }
                        }

                        // South face (Z+)
                        if (neighbors.isAir(x, y, z + 1)) {
                            hasFaces = true;
                            float ao00 = vertexAO(neighbors, x - 1, y, z + 1, x, y - 1, z + 1, x - 1, y - 1, z + 1);
                            float ao10 = vertexAO(neighbors, x + 1, y, z + 1, x, y - 1, z + 1, x + 1, y - 1, z + 1);
                            float ao11 = vertexAO(neighbors, x + 1, y, z + 1, x, y + 1, z + 1, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(neighbors, x - 1, y, z + 1, x, y + 1, z + 1, x - 1, y + 1, z + 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(writer, x, y, z1, rSide, gSide, bSide, ao00);
                                v(writer, x1, y, z1, rSide, gSide, bSide, ao10);
                                v(writer, x1, y1, z1, rSide, gSide, bSide, ao11);
                                v(writer, x, y1, z1, rSide, gSide, bSide, ao01);
                            } else {
                                v(writer, x1, y, z1, rSide, gSide, bSide, ao10);
                                v(writer, x1, y1, z1, rSide, gSide, bSide, ao11);
                                v(writer, x, y1, z1, rSide, gSide, bSide, ao01);
                                v(writer, x, y, z1, rSide, gSide, bSide, ao00);
                            }
                        }

                        // West face (X-)
                        if (neighbors.isAir(x - 1, y, z)) {
                            hasFaces = true;
                            float ao00 = vertexAO(neighbors, x - 1, y, z - 1, x - 1, y - 1, z, x - 1, y - 1, z - 1);
                            float ao10 = vertexAO(neighbors, x - 1, y, z + 1, x - 1, y - 1, z, x - 1, y - 1, z + 1);
                            float ao11 = vertexAO(neighbors, x - 1, y, z + 1, x - 1, y + 1, z, x - 1, y + 1, z + 1);
                            float ao01 = vertexAO(neighbors, x - 1, y, z - 1, x - 1, y + 1, z, x - 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(writer, x, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(writer, x, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(writer, x, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(writer, x, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                            } else {
                                v(writer, x, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(writer, x, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(writer, x, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(writer, x, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                            }
                        }

                        // East face (X+)
                        if (neighbors.isAir(x + 1, y, z)) {
                            hasFaces = true;
                            float ao00 = vertexAO(neighbors, x + 1, y, z - 1, x + 1, y - 1, z, x + 1, y - 1, z - 1);
                            float ao10 = vertexAO(neighbors, x + 1, y, z + 1, x + 1, y - 1, z, x + 1, y - 1, z + 1);
                            float ao11 = vertexAO(neighbors, x + 1, y, z + 1, x + 1, y + 1, z, x + 1, y + 1, z + 1);
                            float ao01 = vertexAO(neighbors, x + 1, y, z - 1, x + 1, y + 1, z, x + 1, y + 1, z - 1);
                            if (ao00 + ao11 > ao01 + ao10) {
                                v(writer, x1, y, z, rSideEW, gSideEW, bSideEW, ao00);
                                v(writer, x1, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(writer, x1, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(writer, x1, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                            } else {
                                v(writer, x1, y1, z, rSideEW, gSideEW, bSideEW, ao01);
                                v(writer, x1, y1, z1, rSideEW, gSideEW, bSideEW, ao11);
                                v(writer, x1, y, z1, rSideEW, gSideEW, bSideEW, ao10);
                                v(writer, x1, y, z, rSideEW, gSideEW, bSideEW, ao00);
                            }
                        }
                    }
                }
            }

            if (!hasFaces) return EMPTY;
            try (MeshData mesh = builder.buildOrThrow()) {
                byte[] vertices = new byte[mesh.vertexBuffer().remaining()];
                mesh.vertexBuffer().get(vertices);
                return vertices;
            }
        } finally {
            TesselatorPool.release(tesselator);
        }
    }

    // Lowest stored surface of a chunk and its four direct neighbors
    private static int getMinSurrounding(ChunkNeighborhood neighbors) {
        int min = getSurface(neighbors.center());
        min = Math.min(min, getSurface(neighbors.north()));
        min = Math.min(min, getSurface(neighbors.south()));
        min = Math.min(min, getSurface(neighbors.west()));
        min = Math.min(min, getSurface(neighbors.east()));
        return min;
    }

    private static int getSurface(LodChunkData chunk) {
        return chunk == null || chunk.empty() ? 0 : chunk.minSurface();
    }

    private static int positionNoise(int x, int y, int z) {
        int hash = x * 73428767 ^ y * 912931 ^ z * 438289;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return Math.floorMod(hash, 15) - 7;
    }

    private record VertexWriter(BufferBuilder builder, int xOffset, int zOffset) {
    }

    private static void v(VertexWriter writer, float x, float y, float z, float r, float g, float col, float ao) {
        writer.builder().addVertex(x + writer.xOffset(), y, z + writer.zOffset())
                .setColor((int) (r * ao), (int) (g * ao), (int) (col * ao), 255);
    }

    private static float vertexAO(
            ChunkNeighborhood neighbors,
            int sx, int sy, int sz,
            int cx, int cy, int cz,
            int ex, int ey, int ez
    ) {
        boolean side1 = !neighbors.isAir(sx, sy, sz);
        boolean side2 = !neighbors.isAir(cx, cy, cz);
        boolean corner = !neighbors.isAir(ex, ey, ez);
        if (side1 && side2) return 0.5F;
        return (3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0))) / 6.0f + 0.5f;
    }

    private record ChunkNeighborhood(
            LodChunkData center,
            LodChunkData north,
            LodChunkData south,
            LodChunkData west,
            LodChunkData east,
            LodChunkData northWest,
            LodChunkData northEast,
            LodChunkData southWest,
            LodChunkData southEast,
            CaveSlice slice
    ) {
        private ChunkNeighborhood(
                LodChunkData center,
                LodChunkData north,
                LodChunkData south,
                LodChunkData west,
                LodChunkData east,
                LodChunkData northWest,
                LodChunkData northEast,
                LodChunkData southWest,
                LodChunkData southEast
        ) {
            this(center, north, south, west, east, northWest, northEast, southWest, southEast, null);
        }

        private ChunkNeighborhood withSlice(CaveSlice slice) {
            return new ChunkNeighborhood(center, north, south, west, east, northWest, northEast, southWest, southEast, slice);
        }

        private boolean isAir(int x, int y, int z) {
            // Cells the cave slice hides return as air so its cut face gets drawn
            if (slice != null && slice.isHidden(x, y, z)) {
                return true;
            }
            return blockAt(x, y, z) == 0;
        }

        // Raw block color id of a cell, 0 for air and for anything outside the loaded chunks
        private int blockAt(int x, int y, int z) {
            LodChunkData chunk = getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            return chunk == null ? 0 : chunk.getBlock(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
        }

        private LodChunkData getChunk(int chunkX, int chunkZ) {
            return switch (chunkX) {
                case -1 -> switch (chunkZ) {
                    case -1 -> northWest;
                    case 0 -> west;
                    case 1 -> southWest;
                    default -> null;
                };
                case 0 -> switch (chunkZ) {
                    case -1 -> north;
                    case 0 -> center;
                    case 1 -> south;
                    default -> null;
                };
                case 1 -> switch (chunkZ) {
                    case -1 -> northEast;
                    case 0 -> east;
                    case 1 -> southEast;
                    default -> null;
                };
                default -> null;
            };
        }
    }

    // Per column cave view cut heights for a 3x3 chunk neighborhood. Only depends on each column's own
    // blocks, so it stays consistent across chunk borders.
    private static final class CaveSlice {
        private static final int COLUMNS_PER_CHUNK = 16 * 16;
        private static final int CHUNK_COUNT = 9;

        // Cut Y per column of the 9 chunks, indexed by chunkIndex(chunkX, chunkZ).
        private final int[] cut = new int[CHUNK_COUNT * COLUMNS_PER_CHUNK];

        private CaveSlice(ChunkNeighborhood neighbors, int baseline) {
            int limit = baseline + CAVE_WALL_HEIGHT - 1;
            for (int chunkX = -1; chunkX <= 1; chunkX++) {
                for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                    LodChunkData chunk = neighbors.getChunk(chunkX, chunkZ);
                    int base = chunkIndex(chunkX, chunkZ) * COLUMNS_PER_CHUNK;
                    if (chunk == null || chunk.empty()) {
                        Arrays.fill(cut, base, base + COLUMNS_PER_CHUNK, Integer.MAX_VALUE);
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            cut[base + x * 16 + z] = computeCut(chunk, x, z, baseline, limit);
                        }
                    }
                }
            }
        }

        // Follows solids upward from the baseline to keep cave walls visible. A column that already starts
        // with air is cut right at the baseline.
        private static int computeCut(LodChunkData chunk, int x, int z, int baseline, int limit) {
            int height = chunk.getHeight();
            int y = Math.min(baseline, height - 1);
            if (chunk.getBlock(x, y, z) == 0) {
                return y;
            }

            int cutY = y;
            int window = Math.min(limit, height - 1);
            while (cutY < window && chunk.getBlock(x, cutY + 1, z) != 0) {
                cutY++;
            }
            return cutY;
        }

        private static int chunkIndex(int chunkX, int chunkZ) {
            return (chunkX + 1) * 3 + (chunkZ + 1);
        }

        private boolean isHidden(int x, int y, int z) {
            int chunkX = Math.floorDiv(x, 16);
            int chunkZ = Math.floorDiv(z, 16);
            if (chunkX < -1 || chunkX > 1 || chunkZ < -1 || chunkZ > 1) {
                return false;
            }
            return y > cut[chunkIndex(chunkX, chunkZ) * COLUMNS_PER_CHUNK + Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
        }

        private int centerCut(int x, int z) {
            return cut[chunkIndex(0, 0) * COLUMNS_PER_CHUNK + x * 16 + z];
        }
    }
}
