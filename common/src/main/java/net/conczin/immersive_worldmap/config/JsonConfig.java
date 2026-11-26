package net.conczin.immersive_worldmap.config;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class JsonConfig {
    public static final Logger LOGGER = LogManager.getLogger();

    public int version = 0;
    public final String name;

    int getVersion() {
        return 1;
    }

    public JsonConfig(String name) {
        this.name = name;
    }

    public static File getConfigFile(String id) {
        return new File("./config/" + id + ".json");
    }

    public void save() {
        File file = getConfigFile(name);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            version = getVersion();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error(e);
        }
    }

    public static <T extends JsonConfig> T loadOrCreate(T defaultConfig, Class<T> jsonClass) {
        String name = defaultConfig.name;
        if (getConfigFile(name).exists()) {
            try (FileReader reader = new FileReader(getConfigFile(name))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonElement fileVersion = json.get("version");

                if (fileVersion == null || fileVersion.getAsInt() != defaultConfig.getVersion()) {
                    LOGGER.info("Config '{}' has a different version and was reset to its defaults.", name);
                    defaultConfig.save();
                    return defaultConfig;
                }

                Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(jsonClass, (InstanceCreator<T>) type -> defaultConfig).create();
                T config = gson.fromJson(json, jsonClass);
                config.save();
                return config;
            } catch (Exception e) {
                LOGGER.error("Failed to load config for '{}'! Default config is used for now. Delete the file to reset.", name);
                LOGGER.error(e);
                return defaultConfig;
            }
        } else {
            defaultConfig.save();
            return defaultConfig;
        }
    }
}
