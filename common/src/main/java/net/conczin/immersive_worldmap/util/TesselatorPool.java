package net.conczin.immersive_worldmap.util;

import com.mojang.blaze3d.vertex.Tesselator;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class TesselatorPool {
    private static final ConcurrentLinkedQueue<Tesselator> POOL = new ConcurrentLinkedQueue<>();

    public static Tesselator acquire() {
        Tesselator tesselator = POOL.poll();
        return tesselator != null ? tesselator : new Tesselator(65536);
    }

    public static void release(Tesselator tesselator) {
        if (tesselator != null) {
            POOL.offer(tesselator);
        }
    }
}
