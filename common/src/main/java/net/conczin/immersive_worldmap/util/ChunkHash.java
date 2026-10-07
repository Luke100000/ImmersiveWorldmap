package net.conczin.immersive_worldmap.util;

import io.netty.buffer.Unpooled;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.List;
import java.util.stream.LongStream;

public final class ChunkHash {
    private static final XXHash64 HASH = XXHashFactory.fastestInstance().hash64();

    private ChunkHash() {
    }

    public static long of(List<PalettedContainer<BlockState>> sections) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(sections.size());
            for (PalettedContainer<BlockState> section : sections) {
                buffer.writeBoolean(section != null);
                if (section == null) continue;
                var packed = section.pack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES);
                buffer.writeVarInt(packed.paletteEntries().size());
                for (BlockState state : packed.paletteEntries()) {
                    buffer.writeVarInt(Block.getId(state));
                }
                buffer.writeLongArray(packed.storage().map(LongStream::toArray).orElseGet(() -> new long[0]));
            }
            int length = buffer.writerIndex();
            return HASH.hash(buffer.nioBuffer(0, length), 0, length, 0);
        } finally {
            buffer.release();
        }
    }
}
