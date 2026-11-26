package net.conczin.immersive_worldmap.util;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class ByteBufferBuilderPool {
    private static final ConcurrentLinkedQueue<ByteBufferBuilder> POOL = new ConcurrentLinkedQueue<>();

    public static ByteBufferBuilder acquire() {
        ByteBufferBuilder builder = POOL.poll();
        return builder != null ? builder : new ByteBufferBuilder(65536);
    }

    public static void release(ByteBufferBuilder builder) {
        if (builder != null) {
            builder.clear();
            POOL.offer(builder);
        }
    }
}
