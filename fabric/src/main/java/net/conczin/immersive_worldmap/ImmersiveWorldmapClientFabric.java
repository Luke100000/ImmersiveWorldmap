package net.conczin.immersive_worldmap;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.conczin.immersive_worldmap.renderer.LodMapRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.renderer.MultiBufferSource;

public class ImmersiveWorldmapClientFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ImmersiveWorldmapClient.init();

        KeyBindingsFabric.register();
        PictureInPictureRendererRegistry.register(context ->
                new LodMapRenderer(MultiBufferSource.immediate(new ByteBufferBuilder(256))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ImmersiveWorldmapClient.start());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ImmersiveWorldmapClient.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ImmersiveWorldmapClient.onClientTick());
    }
}
