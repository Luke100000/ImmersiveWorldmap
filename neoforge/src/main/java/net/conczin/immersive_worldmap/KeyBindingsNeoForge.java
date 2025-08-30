package net.conczin.immersive_worldmap;

import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ImmersiveWorldmap.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyBindingsNeoForge {
    public static final KeyMapping MAP_VIEWER = new KeyMapping(
            "key.immersive_worldmap.open_lod_viewer",
            GLFW.GLFW_KEY_M,
            "category.immersive_worldmap"
    );

    @SubscribeEvent
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        event.register(MAP_VIEWER);
    }
}

