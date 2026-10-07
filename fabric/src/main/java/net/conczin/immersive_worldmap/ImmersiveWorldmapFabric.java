package net.conczin.immersive_worldmap;

import net.fabricmc.api.ModInitializer;

public class ImmersiveWorldmapFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ImmersiveWorldmap.init();
    }
}
