package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Unique
    private final Map<ChunkPos, Long> immersiveWorldmap$dirtyChunks = new LinkedHashMap<>();

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        ChunkPos chunkPos = new ChunkPos(pos);
        immersiveWorldmap$dirtyChunks.remove(chunkPos);
        immersiveWorldmap$dirtyChunks.put(chunkPos, System.nanoTime());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        Iterator<Map.Entry<ChunkPos, Long>> iterator = immersiveWorldmap$dirtyChunks.entrySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        Map.Entry<ChunkPos, Long> entry = iterator.next();
        if (System.nanoTime() - entry.getValue() < 60_000_000_000L) {
            return;
        }
        iterator.remove();

        ChunkPos pos = entry.getKey();
        LevelChunk chunk = ((ClientLevel) (Object) this).getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (chunk != null) {
            ChunkLodProcessor.processChunk(chunk);
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void onUnload(LevelChunk chunk, CallbackInfo ci) {
        if (immersiveWorldmap$dirtyChunks.remove(chunk.getPos()) != null) {
            ChunkLodProcessor.processChunk(chunk);
        }
    }
}
