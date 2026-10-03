package net.conczin.immersive_worldmap.util;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.network.FriendlyByteBuf;

public final class ChunkHash {
    private static final XXHash64 HASH = XXHashFactory.fastestInstance().hash64();

    private ChunkHash() {
    }

    public static long of(FriendlyByteBuf buffer) {
        int length = buffer.writerIndex();
        return HASH.hash(buffer.nioBuffer(0, length), 0, length, 0);
    }
}
