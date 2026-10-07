package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.lod.ServerChunkRecorder;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void onStart(CallbackInfo ci) {
        ServerChunkRecorder.start((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerChunkRecorder.tick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void onStop(CallbackInfo ci) {
        ServerChunkRecorder.stop((MinecraftServer) (Object) this);
    }
}
