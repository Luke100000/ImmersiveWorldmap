package net.conczin.immersive_worldmap.database;

import net.conczin.immersive_worldmap.ImmersiveWorldmap;
import net.conczin.immersive_worldmap.lod.LodChunkData;
import net.conczin.immersive_worldmap.util.CompressionUtil;
import net.conczin.immersive_worldmap.util.PooledBytes;

import java.nio.file.Path;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkLodDatabase implements AutoCloseable {
    public record StoredChunk(boolean exists, PooledBytes colors, boolean dirty, int minSurface) {
    }

    @FunctionalInterface
    private interface Transaction {
        void run(Connection connection) throws SQLException;
    }

    private final String databaseUrl;
    private final ThreadLocal<Connection> threadConnection = new ThreadLocal<>();
    private final ThreadLocal<Map<String, PreparedStatement>> threadStatements = ThreadLocal.withInitial(HashMap::new);
    private final Set<Connection> openConnections = ConcurrentHashMap.newKeySet();
    private final Set<PreparedStatement> openStatements = ConcurrentHashMap.newKeySet();
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
        try (Connection connection = openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
            initializeSchema(connection);
        } catch (SQLException error) {
            closed = true;
            ImmersiveWorldmap.LOGGER.error("Failed to initialize database at {}", databasePath, error);
        }
    }

    boolean isClosed() {
        return closed;
    }

    private Connection getConnection() throws SQLException {
        Connection existing = threadConnection.get();
        if (existing != null && !existing.isClosed()) return existing;

        synchronized (lifecycleLock) {
            if (closed) {
                throw new SQLException("Database is closed");
            }
            Connection connection = openConnection();
            openConnections.add(connection);
            threadConnection.set(connection);
            threadStatements.remove();
            return connection;
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return connection;
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        // TODO: Evict invalidated statements so SQL failures cannot permanently break recording on this connection.
        Map<String, PreparedStatement> statements = threadStatements.get();
        PreparedStatement statement = statements.get(sql);
        if (statement == null || statement.isClosed()) {
            statement = connection.prepareStatement(sql);
            statements.put(sql, statement);
            openStatements.add(statement);
        }
        return statement;
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
                        min_surface INTEGER NOT NULL DEFAULT 0,
                        empty INTEGER NOT NULL DEFAULT 0,
                        dirty INTEGER NOT NULL DEFAULT 0,
                        hash INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (x, z, dimension, lod)
                    )
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
    public void upsertChunk(int x, int z, String dimension, int lod, byte[] colors, int minSurface, long chunkHash) throws SQLException {
        inTransaction(connection -> {
            upsertChunkRow(connection, x, z, dimension, lod, colors, minSurface, chunkHash);
            markParentsDirty(connection, x, z, dimension, lod);
        });
    }

    private void inTransaction(Transaction transaction) throws SQLException {
        // TODO: Retire connections after failed rollback to prevent committing partial chunk/parent updates.
        Connection connection = getConnection();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            transaction.run(connection);
            connection.commit();
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException resetError) {
                if (failure == null) {
                    throw resetError;
                }
                failure.addSuppressed(resetError);
            }
        }
    }

    private void upsertChunkRow(Connection connection, int x, int z, String dimension, int lod, byte[] colors, int minSurface, long chunkHash) throws SQLException {
        String sql = """
                INSERT INTO chunk_lod (x, z, dimension, lod, colors, min_surface, empty, dirty, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT(x, z, dimension, lod) DO UPDATE SET
                    colors = excluded.colors,
                    min_surface = excluded.min_surface,
                    empty = excluded.empty,
                    dirty = 0,
                    hash = excluded.hash
                """;

        PreparedStatement pstmt = prepare(connection, sql);
        pstmt.setInt(1, x);
        pstmt.setInt(2, z);
        pstmt.setString(3, dimension);
        pstmt.setInt(4, lod);
        pstmt.setInt(6, minSurface);
        pstmt.setLong(8, chunkHash);
        if (colors == null) {
            pstmt.setNull(5, Types.BLOB);
            pstmt.setBoolean(7, true);
        } else {
            pstmt.setBytes(5, CompressionUtil.compress(colors));
            pstmt.setBoolean(7, false);
        }
        pstmt.executeUpdate();
    }

    private void markParentsDirty(Connection connection, int x, int z, String dimension, int lod) throws SQLException {
        String sql = "UPDATE chunk_lod SET dirty = 1 WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        PreparedStatement statement = prepare(connection, sql);
        try {
            for (int parentLod = lod + 1; parentLod <= LodChunkData.MAX_LOD; parentLod++) {
                x = Math.floorDiv(x, 2);
                z = Math.floorDiv(z, 2);
                statement.setInt(1, x);
                statement.setInt(2, z);
                statement.setString(3, dimension);
                statement.setInt(4, parentLod);
                statement.addBatch();
            }
            statement.executeBatch();
        } finally {
            statement.clearBatch();
        }
    }

    /**
     * Retrieves the color data for a specific chunk.
     *
     * @param x         the x coordinate
     * @param z         the z coordinate
     * @param dimension the dimension identifier
     * @param lod       the level of detail
     * @return the stored record; colors are null for empty, missing, or dirty chunks
     * @throws SQLException if a database access error occurs
     */
    public StoredChunk loadChunk(int x, int z, String dimension, int lod) throws SQLException {
        Connection connection = getConnection();
        String sql = "SELECT colors, dirty, min_surface FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        PreparedStatement pstmt = prepare(connection, sql);
        pstmt.setInt(1, x);
        pstmt.setInt(2, z);
        pstmt.setString(3, dimension);
        pstmt.setInt(4, lod);

        try (ResultSet rs = pstmt.executeQuery()) {
            if (!rs.next()) return new StoredChunk(false, null, false, 0);
            int minSurface = rs.getInt("min_surface");
            if (rs.getBoolean("dirty")) {
                return new StoredChunk(true, null, true, minSurface);
            }

            byte[] compressed = rs.getBytes("colors");
            try {
                PooledBytes colors = compressed == null ? null : CompressionUtil.decompress(compressed);
                return new StoredChunk(true, colors, false, minSurface);
            } catch (RuntimeException e) {
                throw new SQLException("Invalid chunk data at " + x + ", " + z + " in " + dimension + " (LOD " + lod + ")", e);
            }
        }
    }

    public Long getChunkHash(int x, int z, String dimension, int lod) throws SQLException {
        Connection connection = getConnection();
        String sql = "SELECT hash FROM chunk_lod WHERE x = ? AND z = ? AND dimension = ? AND lod = ?";
        PreparedStatement pstmt = prepare(connection, sql);
        pstmt.setInt(1, x);
        pstmt.setInt(2, z);
        pstmt.setString(3, dimension);
        pstmt.setInt(4, lod);
        try (ResultSet rs = pstmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    public boolean hasRecordedChunks(int x, int z, String dimension, int lod) throws SQLException {
        long size = 1L << lod;
        PreparedStatement statement = prepare(getConnection(), """
                SELECT 1 FROM chunk_lod
                WHERE x >= ? AND x < ? AND z >= ? AND z < ? AND dimension = ? AND lod = 0
                LIMIT 1
                """);
        statement.setLong(1, x * size);
        statement.setLong(2, (x + 1L) * size);
        statement.setLong(3, z * size);
        statement.setLong(4, (z + 1L) * size);
        statement.setString(5, dimension);
        try (ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    /**
     * Clears all chunks and lods for a specific dimension.
     *
     * @param dimension the dimension identifier
     */
    public void clearDimension(String dimension) {
        clear("DELETE FROM chunk_lod WHERE dimension = ?", dimension);
    }

    /**
     * Clears all lods for a specific dimension.
     *
     * @param dimension the dimension identifier
     */
    public void clearGeneratedLods(String dimension) {
        clear("DELETE FROM chunk_lod WHERE dimension = ? AND lod > 0", dimension);
    }

    private void clear(String sql, String dimension) {
        try {
            Connection connection = getConnection();
            PreparedStatement pstmt = prepare(connection, sql);
            pstmt.setString(1, dimension);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            threadStatements.get().remove(sql);
            ImmersiveWorldmap.LOGGER.error("Failed to clear LOD data for {}", dimension, e);
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            for (PreparedStatement statement : openStatements) {
                try {
                    statement.close();
                } catch (SQLException error) {
                    ImmersiveWorldmap.LOGGER.error("Failed to close database statement", error);
                }
            }

            openStatements.clear();
            threadStatements.remove();

            SQLException failure = null;
            for (Connection connection : openConnections) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }

            openConnections.clear();
            threadConnection.remove();

            if (failure != null) {
                ImmersiveWorldmap.LOGGER.error("Failed to close database connections", failure);
            }
        }
    }
}
