package net.conczin.immersive_worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ImmersiveWorldmapFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ImmersiveWorldmap.init();
        KeyBindingsFabric.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ImmersiveWorldmap.initializeDatabase());

    }
}
