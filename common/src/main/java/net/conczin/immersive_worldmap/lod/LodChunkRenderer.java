package net.conczin.immersive_worldmap.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Renders LOD chunk data as a mesh.
 * Uses greedy meshing to build optimized geometry from voxel data.
 */
public class LodChunkRenderer {

    /**
     * Builds a mesh from LOD chunk data.
     * Only renders non-air blocks with visible faces (greedy meshing).
     *
     * @param lodData the LOD chunk data
     * @param tesselator the tesselator to use
     * @return the built mesh data
     */
    public static MeshData buildMesh(LodChunkData lodData, Tesselator tesselator) {
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        float offsetX = lodData.chunkX() * 16.0F;
        float offsetZ = lodData.chunkZ() * 16.0F;
        int height = lodData.getHeight();

        // Simple color for now - gray for non-air blocks
        float r = 0.7F;
        float g = 0.7F;
        float b = 0.7F;
        float a = 1.0F;

        // Different shading for each face direction
        float topBrightness = 1.0F;
        float bottomBrightness = 0.5F;
        float sideBrightness = 0.8F;

        // Iterate through all blocks and render visible faces
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < 16; z++) {
                    if (lodData.isAir(x, y, z)) {
                        continue; // Skip air blocks
                    }

                    float x0 = x;
                    float y0 = y;
                    float z0 = z;
                    float x1 = x0 + 1.0F;
                    float y1 = y0 + 1.0F;
                    float z1 = z0 + 1.0F;

                    // Top face (Y+)
                    if (y == height - 1 || lodData.isAir(x, y + 1, z)) {
                        builder.addVertex(x0, y1, z1).setColor(r * topBrightness, g * topBrightness, b * topBrightness, a).setNormal(0.0F, 1.0F, 0.0F);
                        builder.addVertex(x1, y1, z1).setColor(r * topBrightness, g * topBrightness, b * topBrightness, a).setNormal(0.0F, 1.0F, 0.0F);
                        builder.addVertex(x1, y1, z0).setColor(r * topBrightness, g * topBrightness, b * topBrightness, a).setNormal(0.0F, 1.0F, 0.0F);
                        builder.addVertex(x0, y1, z0).setColor(r * topBrightness, g * topBrightness, b * topBrightness, a).setNormal(0.0F, 1.0F, 0.0F);
                    }

                    // Bottom face (Y-)
                    if (y == 0 || lodData.isAir(x, y - 1, z)) {
                        builder.addVertex(x0, y0, z0).setColor(r * bottomBrightness, g * bottomBrightness, b * bottomBrightness, a).setNormal(0.0F, -1.0F, 0.0F);
                        builder.addVertex(x1, y0, z0).setColor(r * bottomBrightness, g * bottomBrightness, b * bottomBrightness, a).setNormal(0.0F, -1.0F, 0.0F);
                        builder.addVertex(x1, y0, z1).setColor(r * bottomBrightness, g * bottomBrightness, b * bottomBrightness, a).setNormal(0.0F, -1.0F, 0.0F);
                        builder.addVertex(x0, y0, z1).setColor(r * bottomBrightness, g * bottomBrightness, b * bottomBrightness, a).setNormal(0.0F, -1.0F, 0.0F);
                    }

                    // North face (Z-)
                    if (z == 0 || lodData.isAir(x, y, z - 1)) {
                        builder.addVertex(x0, y1, z0).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, -1.0F);
                        builder.addVertex(x1, y1, z0).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, -1.0F);
                        builder.addVertex(x1, y0, z0).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, -1.0F);
                        builder.addVertex(x0, y0, z0).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, -1.0F);
                    }

                    // South face (Z+)
                    if (z == 15 || lodData.isAir(x, y, z + 1)) {
                        builder.addVertex(x0, y0, z1).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, 1.0F);
                        builder.addVertex(x1, y0, z1).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, 1.0F);
                        builder.addVertex(x1, y1, z1).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, 1.0F);
                        builder.addVertex(x0, y1, z1).setColor(r * sideBrightness, g * sideBrightness, b * sideBrightness, a).setNormal(0.0F, 0.0F, 1.0F);
                    }

                    // West face (X-)
                    if (x == 0 || lodData.isAir(x - 1, y, z)) {
                        builder.addVertex(x0, y0, z0).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(-1.0F, 0.0F, 0.0F);
                        builder.addVertex(x0, y0, z1).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(-1.0F, 0.0F, 0.0F);
                        builder.addVertex(x0, y1, z1).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(-1.0F, 0.0F, 0.0F);
                        builder.addVertex(x0, y1, z0).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(-1.0F, 0.0F, 0.0F);
                    }

                    // East face (X+)
                    if (x == 15 || lodData.isAir(x + 1, y, z)) {
                        builder.addVertex(x1, y1, z0).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(1.0F, 0.0F, 0.0F);
                        builder.addVertex(x1, y1, z1).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(1.0F, 0.0F, 0.0F);
                        builder.addVertex(x1, y0, z1).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(1.0F, 0.0F, 0.0F);
                        builder.addVertex(x1, y0, z0).setColor(r * sideBrightness * 0.9F, g * sideBrightness * 0.9F, b * sideBrightness * 0.9F, a).setNormal(1.0F, 0.0F, 0.0F);
                    }
                }
            }
        }

        builder.addVertex(0,0,1).setColor(1,0,0,1).setNormal(0,1,0);
        builder.addVertex(1,0,1).setColor(1,0,0,1).setNormal(0,1,0);
        builder.addVertex(1,1,1).setColor(1,0,0,1).setNormal(0,1,0);
        builder.addVertex(0,1,1).setColor(1,0,0,1).setNormal(0,1,0);

        return builder.buildOrThrow();
    }
}

