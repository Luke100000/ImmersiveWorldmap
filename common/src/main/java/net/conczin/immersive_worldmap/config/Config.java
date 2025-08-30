package net.conczin.immersive_worldmap.config;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;

public final class Config extends JsonConfig {
    private static Config INSTANCE = loadOrCreate(new Config(), Config.class);

    public Config() {
        super(ImmersiveWorldmap.MOD_ID);
    }

    public static Config getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unused")
    public String _documentation = "https://github.com/Luke100000/ImmersiveWorldmap/wiki";

    public boolean enableEntities = true;
}
