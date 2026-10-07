package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void onLoaded(boolean loaded, CallbackInfo ci) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel level) {
            if (loaded) {
                ServerChunkRecorder.record(chunk, level);
            } else {
                ServerChunkRecorder.unload(chunk, level);
            }
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onBlockChanged(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (cir.getReturnValue() != null && chunk.getLevel() instanceof ServerLevel level) {
            ServerChunkRecorder.markDirty(chunk, level);
        }
    }
}
