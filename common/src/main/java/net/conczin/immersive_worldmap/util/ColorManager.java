package net.conczin.immersive_worldmap.util;

import net.minecraft.world.level.material.MapColor;

public class ColorManager {
    private static final int[][] COLORS = new int[64][3];

    static {
        for (int id = 0; id < 64; id++) {
            MapColor mapColor = MapColor.byId(id);

            int packedColor = mapColor.calculateARGBColor(MapColor.Brightness.NORMAL);
            int red = packedColor & 0xFF;
            int green = (packedColor >> 8) & 0xFF;
            int blue = (packedColor >> 16) & 0xFF;

            COLORS[id][0] = red;
            COLORS[id][1] = green;
            COLORS[id][2] = blue;
        }
    }

    public static int[] byteToRGB(byte colorId) {
        int index = colorId & 0x3F;
        return COLORS[index];
    }
}

