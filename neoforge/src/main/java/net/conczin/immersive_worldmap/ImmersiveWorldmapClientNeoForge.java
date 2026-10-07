package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, value = Dist.CLIENT)
public class ImmersiveWorldmapClientNeoForge {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ImmersiveWorldmapClient.onClientTick();

        Minecraft client = Minecraft.getInstance();
        while (ImmersiveWorldmapClient.MAP_VIEWER_KEY.consumeClick()) {
            if (client.screen == null && client.player != null && client.level != null) {
                client.setScreen(new LodViewerScreen());
            }
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(ClientPlayerNetworkEvent.LoggingIn event) {
        ImmersiveWorldmapClient.start();
    }

    @SubscribeEvent
    public static void onWorldUnload(ClientPlayerNetworkEvent.LoggingOut event) {
        ImmersiveWorldmapClient.shutdown();
    }
}
