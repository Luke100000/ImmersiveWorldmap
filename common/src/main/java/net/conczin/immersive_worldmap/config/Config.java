package net.conczin.immersive_worldmap.config;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;

public final class Config extends JsonConfig {
    private static final Config INSTANCE = loadOrCreate(new Config(ImmersiveWorldmap.MOD_ID), Config.class);

    // Cave view slices away the ceiling above the player so caves and buildings can be inspected.
    // Disabling it removes the toggle key and the automatic switch.
    public boolean enableCaveView = true;

    // Switch to cave view automatically when the map is opened below a roof.
    public boolean autoCaveView = true;

    // Camera fly-in and fade when opening the map.
    public boolean openingAnimation = true;

    // Show the controls overlay at the bottom of the map.
    public boolean showControls = true;

    // Degrees per pixel of mouse movement while orbiting.
    public float rotationSensitivity = 0.4f;

    // Chunks around the camera at which the first, coarser level of detail starts. Higher values look sharper but cost more.
    public double detailDistance = 12;

    // Draw distance relative to the zoom. Higher values show more terrain but cost more.
    public double viewDistance = 48;

    // After closing the map, loaded map data is kept for this many minutes (or until you move far away).
    public int retentionMinutes = 5;

    // Background threads used to process chunks. 0 selects them automatically.
    public int workerThreads = 0;

    public Config(String name) {
        super(name);
    }

    public static Config getInstance() {
        return INSTANCE;
    }

    public boolean isCaveViewEnabled() {
        return enableCaveView;
    }

    public int getWorkerThreads() {
        return workerThreads > 0 ? workerThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }
}
