package net.conczin.immersive_worldmap;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ImmersiveWorldmap {
    public static final String MOD_ID = "immersive_worldmap";
    public static final String MOD_NAME = "Immersive Worldmap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public static ResourceLocation locate(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static String databaseName(String type, String identifier) {
        return UUID.nameUUIDFromBytes((type + ":" + identifier).getBytes(StandardCharsets.UTF_8)) + ".db";
    }
}
