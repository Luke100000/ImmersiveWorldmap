package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.ImmersiveWorldmapClient;
import net.conczin.immersive_worldmap.lod.ChunkLodProcessor;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public class ClientChunkCacheMixin {
    @Inject(
            method = "replaceWithPacketData",
            at = @At("RETURN")
    )
    private void onReplaceWithPacketData(int pX, int pZ, ClientboundLevelChunkPacketData pData, CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null && ImmersiveWorldmapClient.shouldRecordChunks()) {
            ChunkLodProcessor.processChunk(chunk, chunk.getLevel());
        }
    }
}

