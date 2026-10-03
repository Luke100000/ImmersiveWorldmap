package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@Mod(value = ImmersiveWorldmap.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, value = Dist.CLIENT)
public class ImmersiveWorldmapNeoForge {
    public ImmersiveWorldmapNeoForge() {
        ImmersiveWorldmap.init();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ImmersiveWorldmap.onClientTick();

        Minecraft client = Minecraft.getInstance();
        while (ImmersiveWorldmap.MAP_VIEWER_KEY.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new LodViewerScreen());
            }
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(ClientPlayerNetworkEvent.LoggingIn event) {
        ImmersiveWorldmap.start();
    }

    @SubscribeEvent
    public static void onWorldUnload(ClientPlayerNetworkEvent.LoggingOut event) {
        ImmersiveWorldmap.shutdown();
    }
}
