package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

public class KeyBindingsFabric {
    public static void register() {
        KeyBindingHelper.registerKeyBinding(ImmersiveWorldmap.MAP_VIEWER_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ImmersiveWorldmap.MAP_VIEWER_KEY.consumeClick()) {
                if (client.screen == null && client.player != null && client.level != null) {
                    client.setScreen(new LodViewerScreen());
                }
            }
        });
    }
}
