package net.conczin.immersive_worldmap.util;

import net.minecraft.world.level.material.MapColor;

public class ColorManager {
    private static final int[][] COLORS = new int[64][4];

    static {
        initializeColorTable();
    }

    private static void initializeColorTable() {
        for (int id = 0; id < 64; id++) {
            MapColor mapColor = MapColor.byId(id);

            int packedColor = mapColor.calculateRGBColor(MapColor.Brightness.NORMAL);
            int alpha = (packedColor >> 24) & 0xFF;
            int red = (packedColor >> 16) & 0xFF;
            int green = (packedColor >> 8) & 0xFF;
            int blue = packedColor & 0xFF;

            // Store in lookup table as [R, G, B, A]
            COLORS[id][0] = red;
            COLORS[id][1] = green;
            COLORS[id][2] = blue;
            COLORS[id][3] = alpha;
        }
    }

    public static int[] byteToRGBA(byte colorId) {
        int index = colorId & 0x3F;
        return COLORS[index];
    }
}

