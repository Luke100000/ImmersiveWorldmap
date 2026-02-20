package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KeyBindingsFabric {
    public static final KeyMapping LOD_VIEWER_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.immersive_worldmap.open_lod_viewer",
            GLFW.GLFW_KEY_M,
            "category.immersive_worldmap"
    ));

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (LOD_VIEWER_KEY.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new LodViewerScreen());
                }
            }
        });
    }
}

