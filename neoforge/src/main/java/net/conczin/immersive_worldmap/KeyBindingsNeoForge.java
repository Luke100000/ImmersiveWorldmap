package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.renderer.LodMapRenderState;
import net.conczin.immersive_worldmap.renderer.LodMapRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;

@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, value = Dist.CLIENT)
public class KeyBindingsNeoForge {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ImmersiveWorldmapClient.init();
    }

    @SubscribeEvent
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        event.register(ImmersiveWorldmapClient.MAP_VIEWER_KEY);
    }

    @SubscribeEvent
    public static void registerPictureInPictureRenderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(LodMapRenderState.class, LodMapRenderer::new);
    }
}
