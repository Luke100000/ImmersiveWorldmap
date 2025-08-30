package net.conczin.immersive_worldmap.database;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for managing the chunk LOD database.
 */
public class DatabaseManager {
    private static ChunkLodDatabase INSTANCE;
    private static final Object LOCK = new Object();

    /**
     * Initializes the database with the given path.
     * This should be called once during mod initialization.
     *
     * @param databasePath the path where the SQLite database should be stored
     * @throws RuntimeException if an I/O error occurs while creating directories
     */
    public static void initialize(Path databasePath) {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                throw new IllegalStateException("Database already initialized");
            }

            try {
                // Ensure the parent directory exists
                Files.createDirectories(databasePath.getParent());
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create database directory: " + databasePath.getParent(), e);
            }

            INSTANCE = new ChunkLodDatabase(databasePath);
        }
    }

    /**
     * Gets the database instance.
     *
     * @return the ChunkLodDatabase instance
     * @throws IllegalStateException if the database hasn't been initialized
     */
    public static ChunkLodDatabase getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("Database not initialized. Call initialize() first.");
        }
        return INSTANCE;
    }

    /**
     * Checks if the database is initialized.
     *
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return INSTANCE != null;
    }

    /**
     * Closes the database connection and cleans up resources.
     * This should be called during mod shutdown.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
            }
        }
    }
}


