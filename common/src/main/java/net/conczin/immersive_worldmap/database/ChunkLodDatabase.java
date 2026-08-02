package net.conczin.immersive_worldmap.database;

import net.conczin.immersive_worldmap.util.CompressionUtil;

import java.nio.file.Path;
import java.sql.*;

/**
 * SQLite database manager for storing chunk LOD data.
 * Maintains an SQLite database of (x, y, z, dimension, lod, colors) for each chunk.
 */
public class ChunkLodDatabase implements AutoCloseable {
    private final Connection connection;

    /**
     * Creates or opens an existing SQLite database.
     *
     * @param databasePath the path to the SQLite database file
     */
    public ChunkLodDatabase(Path databasePath) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
        try {
            String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            initializeSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database at " + databasePath, e);
        }
    }

    /**
     * Initializes the database schema if it doesn't exist.
     *
     * @throws SQLException if a database access error occurs
     */
    private void initializeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chunk_lod (
                        x INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        dimension TEXT NOT NULL,
                        lod INTEGER NOT NULL,
                        colors BLOB,
                        empty INTEGER NOT NULL DEFAULT 0,
                        dirty INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (x, z, dimension, lod)
                    )
                    """);

            // Create an index for faster lookups by dimension and LOD
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_dimension_lod
                    ON chunk_lod(dimension, lod)
                    """);

            // Create an index for spatial queries
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_spatial
                    ON chunk_lod(x, z)
                    """);
        }
    }

    /**
     * Inserts or updates a chunk LOD record.
     *
     * @param x         the x coordinate
     * @param z         the z coordinate
     * @param dimension the dimension identifier
     * @param lod       the level of detail
     * @param colors    the binary color data
     * @throws SQLException if a database access error occurs
     */
    public synchronized void upsertChunk(int x, int z, String dimension, int lod, byte[] colors) throws SQLException {
        String sql = """
                INSERT INTO chunk_lod (x, z, dimension, lod, colors, empty, dirty)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT(x, z, dimension, lod) DO UPDATE SET
                    colors = excluded.colors,
                    empty = excluded.empty
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);
            if (colors == null) {
                pstmt.setNull(5, Types.BLOB);
                pstmt.setBoolean(6, true);
            } else {
                pstmt.setBytes(5, CompressionUtil.compress(colors));
                pstmt.setBoolean(6, false);
            }
            pstmt.executeUpdate();
        }

        markParentsDirty(x, z, dimension, lod);
    }

    private void markParentsDirty(int x, int z, String dimension, int lod) throws SQLException {
        int maxLod;
        try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(lod) FROM chunk_lod WHERE dimension = ?")) {
            statement.setString(1, dimension);
            try (ResultSet result = statement.executeQuery()) {
                maxLod = result.next() ? result.getInt(1) : lod;
            }
        }

        String sql = "UPDATE chunk_lod SET dirty = 1 WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parentLod = lod + 1; parentLod <= maxLod; parentLod++) {
                x = Math.floorDiv(x, 2);
                z = Math.floorDiv(z, 2);
                statement.setInt(1, x);
                statement.setInt(2, z);
                statement.setString(3, dimension);
                statement.setInt(4, parentLod);
                statement.executeUpdate();
            }
        }
    }

    /**
     * Retrieves the color data for a specific chunk.
     *
     * @param x         the x coordinate
     * @param z         the z coordinate
     * @param dimension the dimension identifier
     * @param lod       the level of detail
     * @return color data, or null when the chunk is empty or not found
     * @throws SQLException if a database access error occurs
     */
    public synchronized byte[] getChunkColors(int x, int z, String dimension, int lod) throws SQLException {
        String sql = "SELECT colors FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] compressed = rs.getBytes("colors");
                    return compressed == null ? null : CompressionUtil.decompress(compressed);
                }
            }
        }
        return null;
    }

    public synchronized boolean hasChunk(int x, int z, String dimension, int lod) throws SQLException {
        String sql = "SELECT 1 FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized boolean takeDirty(int x, int z, String dimension, int lod) throws SQLException {
        String sql = "UPDATE chunk_lod SET dirty = 0 WHERE x = ? AND z = ? AND dimension = ? AND lod = ? AND dirty = 1";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);
            return pstmt.executeUpdate() == 1;
        }
    }

    public synchronized void markDirty(int x, int z, String dimension, int lod) throws SQLException {
        String sql = "UPDATE chunk_lod SET dirty = 1 WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);
            pstmt.executeUpdate();
        }
    }

    /**
     * Deletes a chunk LOD record.
     *
     * @param x         the x coordinate
     * @param z         the z coordinate
     * @param dimension the dimension identifier
     * @param lod       the level of detail
     * @throws SQLException if a database access error occurs
     */
    public void deleteChunk(int x, int z, String dimension, int lod) throws SQLException {
        String sql = "DELETE FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);
            pstmt.executeUpdate();
        }
    }

    /**
     * Clears all chunks for a specific dimension.
     *
     * @param dimension the dimension identifier
     * @throws SQLException if a database access error occurs
     */
    public void clearDimension(String dimension) throws SQLException {
        String sql = "DELETE FROM chunk_lod WHERE dimension = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dimension);
            pstmt.executeUpdate();
        }
    }

    public synchronized void clearGeneratedLods(String dimension) throws SQLException {
        String sql = "DELETE FROM chunk_lod WHERE dimension = ? AND lod > 0";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dimension);
            pstmt.executeUpdate();
        }
    }

    /**
     * Gets the row count of the chunk_lod table.
     *
     * @return the number of records in the table
     * @throws SQLException if a database access error occurs
     */
    public long getRecordCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM chunk_lod")) {
            if (rs.next()) {
                return rs.getLong("count");
            }
        }
        return 0;
    }

    /**
     * Closes the database connection.
     */
    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close database connection", e);
        }
    }
}
