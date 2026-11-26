package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SerializableChunkData.class)
public class ChunkSerializerMixin {
    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void onSave(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> cir) {
        ServerChunkRecorder.record(chunk, level);
    }
}
