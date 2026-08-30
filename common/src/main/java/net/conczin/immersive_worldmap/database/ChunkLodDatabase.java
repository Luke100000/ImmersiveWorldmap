package net.conczin.immersive_worldmap.database;

import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.CompressionUtil;

import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLodDatabase implements AutoCloseable {
    public record StoredChunk(boolean exists, byte[] colors, boolean dirty) {
    }

    @FunctionalInterface
    private interface Transaction {
        void run(Connection connection) throws SQLException;
    }

    private final String databaseUrl;
    private final ThreadLocal<Connection> threadConnection = new ThreadLocal<>();
    private final Set<Connection> openConnections = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

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
        this.databaseUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            initializeSchema(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database at " + databasePath, e);
        }
    }

    private Connection getConnection() throws SQLException {
        Connection existing = threadConnection.get();
        if (existing != null && !existing.isClosed()) return existing;

        synchronized (lifecycleLock) {
            if (closed) throw new SQLException("Database is closed");
            Connection connection = openConnection();
            openConnections.add(connection);
            threadConnection.set(connection);
            return connection;
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void initializeSchema(Connection connection) throws SQLException {
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
    public void upsertChunk(int x, int z, String dimension, int lod, byte[] colors) throws SQLException {
        inTransaction(connection -> {
            upsertChunkRow(connection, x, z, dimension, lod, colors);
            markParentsDirty(connection, x, z, dimension, lod);
        });
    }

    private void inTransaction(Transaction transaction) throws SQLException {
        Connection connection = getConnection();
        connection.setAutoCommit(false);
        try {
            transaction.run(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void upsertChunkRow(Connection connection, int x, int z, String dimension, int lod, byte[] colors) throws SQLException {
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
    }

    private void markParentsDirty(Connection connection, int x, int z, String dimension, int lod) throws SQLException {
        String sql = "UPDATE chunk_lod SET dirty = 1 WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parentLod = lod + 1; parentLod <= LodChunkData.MAX_LOD; parentLod++) {
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
    public StoredChunk loadChunk(int x, int z, String dimension, int lod) throws SQLException {
        Connection connection = getConnection();
        String sql = "SELECT colors, dirty FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        byte[] colors;
        boolean dirty;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, z);
            pstmt.setString(3, dimension);
            pstmt.setInt(4, lod);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return new StoredChunk(false, null, false);
                byte[] compressed = rs.getBytes("colors");
                colors = compressed == null ? null : CompressionUtil.decompress(compressed);
                dirty = rs.getBoolean("dirty");
            }
        }

        if (dirty) {
            String clearDirtySql = "UPDATE chunk_lod SET dirty = 0 WHERE x = ? AND z = ? AND dimension = ? AND lod = ? AND dirty = 1";
            try (PreparedStatement pstmt = connection.prepareStatement(clearDirtySql)) {
                pstmt.setInt(1, x);
                pstmt.setInt(2, z);
                pstmt.setString(3, dimension);
                pstmt.setInt(4, lod);
                dirty = pstmt.executeUpdate() == 1;
            }
        }
        return new StoredChunk(true, colors, dirty);
    }

    public void markDirty(int x, int z, String dimension, int lod) throws SQLException {
        Connection connection = getConnection();
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
        Connection connection = getConnection();
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
        Connection connection = getConnection();
        String sql = "DELETE FROM chunk_lod WHERE dimension = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dimension);
            pstmt.executeUpdate();
        }
    }

    public void clearGeneratedLods(String dimension) throws SQLException {
        Connection connection = getConnection();
        String sql = "DELETE FROM chunk_lod WHERE dimension = ? AND lod > 0";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, dimension);
            pstmt.executeUpdate();
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            try {
                for (Connection connection : openConnections) {
                    connection.close();
                }
                openConnections.clear();
                threadConnection.remove();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close database connection", e);
            }
        }
    }
}
