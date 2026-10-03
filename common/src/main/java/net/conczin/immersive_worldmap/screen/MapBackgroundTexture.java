package net.conczin.immersive_worldmap.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public final class MapBackgroundTexture {
    public static final int SIZE = 1024;
    private static final String NAME = "immersive_worldmap/background";

    private static ResourceLocation location;

    private MapBackgroundTexture() {
    }

    public static ResourceLocation get() {
        if (location == null) {
            long start = System.nanoTime();
            location = create();
            ImmersiveWorldmap.LOGGER.info("[benchmark] createBackgroundTexture took {} ms", (System.nanoTime() - start) / 1_000_000.0);
        }
        return location;
    }

    private static ResourceLocation create() {
        NativeImage pixels = new NativeImage(SIZE, SIZE, false);
        double half = SIZE / 2.0;
        for (int y = 0; y < SIZE; y++) {
            double dy = (y + 0.5 - half) / half;
            for (int x = 0; x < SIZE; x++) {
                double dx = (x + 0.5 - half) / half;
                double radius = Math.min(1.0, Math.sqrt(dx * dx + dy * dy));
                double falloff = radius * radius * (3.0 - 2.0 * radius);

                int hash = x * 0x1f123bb5 ^ y * 0x5f356495;
                hash ^= hash >>> 16;
                hash *= 0x7feb352d;
                hash ^= hash >>> 15;
                double dither = ((hash >>> 24) / 255.0 - 0.5) * 1.5;

                int r = Math.clamp((int) Math.round(59 - 43 * falloff + dither), 0, 255);
                int g = Math.clamp((int) Math.round(60 - 38 * falloff + dither), 0, 255);
                int b = Math.clamp((int) Math.round(60 - 33 * falloff + dither), 0, 255);
                pixels.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }

        DynamicTexture texture = new DynamicTexture(pixels);
        texture.setFilter(false, false);
        return Minecraft.getInstance().getTextureManager().register(NAME, texture);
    }
}
