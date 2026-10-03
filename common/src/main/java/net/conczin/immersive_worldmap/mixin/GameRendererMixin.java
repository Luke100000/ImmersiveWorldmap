package net.conczin.immersive_worldmap.mixin;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.conczin.immersive_worldmap.renderer.LodChunkPageManager;
import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.Map;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private Map<String, ShaderInstance> shaders;

    @Inject(method = "reloadShaders", at = @At("TAIL"))
    private void immersive_worldmap$loadMapShader(ResourceProvider resources, CallbackInfo ci) throws IOException {
        shaders.put(LodChunkPageManager.SHADER_NAME,
                new ShaderInstance(resources, LodChunkPageManager.SHADER_NAME, DefaultVertexFormat.POSITION_COLOR));
    }

    @ModifyVariable(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean immersive_worldmap$renderLevel(boolean renderLevel) {
        return renderLevel && !(Minecraft.getInstance().screen instanceof LodViewerScreen);
    }
}
