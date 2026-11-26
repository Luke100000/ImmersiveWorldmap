package net.conczin.immersive_worldmap.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesInvoker {
    @Invoker("register")
    static RenderPipeline immersive_worldmap$register(RenderPipeline pipeline) {
        throw new AssertionError();
    }
}
