package net.conczin.immersive_worldmap;

import net.conczin.immersive_worldmap.screen.LodViewerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public class KeyBindingsFabric {
    public static void register() {
        KeyMappingHelper.registerKeyMapping(ImmersiveWorldmapClient.MAP_VIEWER_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ImmersiveWorldmapClient.MAP_VIEWER_KEY.consumeClick()) {
                if (client.gui.screen() == null && client.player != null && client.level != null) {
                    client.gui.setScreen(new LodViewerScreen());
                }
            }
        });
    }
}
