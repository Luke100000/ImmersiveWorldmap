package net.conczin.immersive_worldmap;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyBindingsNeoForge {
    @SubscribeEvent
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        event.register(ImmersiveWorldmap.MAP_VIEWER_KEY);
    }
}

