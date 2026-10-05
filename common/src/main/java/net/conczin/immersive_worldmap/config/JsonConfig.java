package net.conczin.immersive_worldmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        try {
            Files.createDirectories(file.toPath().getParent());
            version = getVersion();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error(e);
        }
    }

    public static <T extends JsonConfig> T loadOrCreate(T defaultConfig, Class<T> jsonClass) {
        String name = defaultConfig.name;
        if (getConfigFile(name).exists()) {
            try {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                T config;
                try (var reader = Files.newBufferedReader(getConfigFile(name).toPath(), StandardCharsets.UTF_8)) {
                    config = gson.fromJson(reader, jsonClass);
                }
                if (config == null || config.version != config.getVersion()) {
                    config = defaultConfig;
                }
                config.save();
                return config;
            } catch (Exception e) {
                LOGGER.error("Could not load config for '{}'. Using defaults. Delete the file to reset it.", name);
                LOGGER.error(e);
                return defaultConfig;
            }
        } else {
            defaultConfig.save();
            return defaultConfig;
        }
    }
}
