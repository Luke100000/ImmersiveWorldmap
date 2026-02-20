package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(value = ImmersiveWorldmap.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, value = Dist.CLIENT)
public class ImmersiveWorldmapNeoForge {
    public ImmersiveWorldmapNeoForge() {
        ImmersiveWorldmap.init();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        while (KeyBindingsNeoForge.MAP_VIEWER.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new LodViewerScreen());
            }
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ClientLevel) {
            ImmersiveWorldmap.initializeDatabase();
        }
    }
}