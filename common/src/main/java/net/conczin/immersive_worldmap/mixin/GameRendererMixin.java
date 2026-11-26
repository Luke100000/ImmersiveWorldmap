package net.conczin.immersive_worldmap.mixin;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @ModifyVariable(method = "extract", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean immersive_worldmap$extractLevel(boolean advanceGameTime) {
        return advanceGameTime && !(Minecraft.getInstance().gui.screen() instanceof LodViewerScreen);
    }
}
