package net.conczin.immersive_worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ImmersiveWorldmapFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ImmersiveWorldmap.init();
        KeyBindingsFabric.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ImmersiveWorldmap.start());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ImmersiveWorldmap.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ImmersiveWorldmap.onClientTick());
    }
}
