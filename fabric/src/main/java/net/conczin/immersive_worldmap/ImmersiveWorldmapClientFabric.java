package net.conczin.immersive_worldmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ImmersiveWorldmapClientFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ImmersiveWorldmapClient.init();

        KeyBindingsFabric.register();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ImmersiveWorldmapClient.start());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ImmersiveWorldmapClient.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ImmersiveWorldmapClient.onClientTick());
    }
}
