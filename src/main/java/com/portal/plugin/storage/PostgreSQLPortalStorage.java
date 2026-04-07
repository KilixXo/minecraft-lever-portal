package com.portal.plugin.storage;

import com.portal.plugin.Main;
import com.portal.plugin.Portal;
import com.portal.plugin.PortalConnection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQLPortalStorage — persists portal data in a PostgreSQL database.
 *
 * <p>Driver: {@code org.postgresql.Driver} (shaded into the plugin jar).
 *
 * <p>PERF-04 fix: {@link #getConnection()} validates the connection before each
 * operation and reconnects automatically if the connection has been closed.
 *
 * <p>Schema:
 * <pre>
 * portals(id, orientation, active, owner_uuid, access_mode,
 *         lever_world, lever_x, lever_y, lever_z)
 *
 * portal_blocks(portal_id, world, x, y, z)
 *
 * portal_players(portal_id, player_uuid, access_type)  -- 'ALLOW' | 'DENY'
 *
 * portal_connections(portal1_id, portal2_id)
 * </pre>
 *
 * <p>PostgreSQL differences from MySQL/SQLite:
 * <ul>
 *   <li>Uses {@code BOOLEAN} instead of {@code TINYINT(1)} for the {@code active} column.</li>
 *   <li>Uses {@code TEXT} instead of {@code VARCHAR} (both work, TEXT is idiomatic in PG).</li>
 * </ul>
 */
public class PostgreSQLPortalStorage implements PortalStorage {

    private final Main plugin;
    private final DatabaseConfig dbConfig;
    private Connection connection;

    public PostgreSQLPortalStorage(Main plugin, DatabaseConfig dbConfig) {
        this.plugin   = plugin;
        this.dbConfig = dbConfig;
    }

    // ── PortalStorage ─────────────────────────────────────────────────────────

    @Override
    public void init() {
        try {
            Class.forName(dbConfig.getDriverClass());
            openConnection();
            createTables();
            plugin.getLogger().info("[Storage] Using PostgreSQL backend: "
                + dbConfig.getHost() + ":" + dbConfig.getPort()
                + "/" + dbConfig.getDatabase());
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("[Storage] PostgreSQL JDBC driver not found: " + e.getMessage());
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to connect to PostgreSQL: " + e.getMessage());
        }
    }

    /**
     * PERF-04 fix: open (or reopen) the database connection.
     */
    private void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
            dbConfig.buildJdbcUrl(),
            dbConfig.getUsername(),
            dbConfig.getPassword()
        );
        connection.setAutoCommit(false);
    }

    /**
     * PERF-04 fix: return a valid connection, reconnecting if the current one
     * has timed out or been closed by the server.
     *
     * @return a valid, open {@link Connection}
     * @throws SQLException if reconnection fails
     */
    private Connection getConnection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            plugin.getLogger().info("[Storage] PostgreSQL connection lost — reconnecting...");
            openConnection();
        }
        return connection;
    }

    private void createTables() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portals (" +
                "  id TEXT NOT NULL PRIMARY KEY," +
                "  orientation TEXT NOT NULL," +
                "  active BOOLEAN NOT NULL DEFAULT FALSE," +
                "  owner_uuid TEXT," +
                "  access_mode TEXT NOT NULL DEFAULT 'PUBLIC'," +
                "  lever_world TEXT," +
                "  lever_x INTEGER," +
                "  lever_y INTEGER," +
                "  lever_z INTEGER" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_blocks (" +
                "  portal_id TEXT NOT NULL," +
                "  world TEXT NOT NULL," +
                "  x INTEGER NOT NULL," +
                "  y INTEGER NOT NULL," +
                "  z INTEGER NOT NULL," +
                "  PRIMARY KEY (portal_id, world, x, y, z)," +
                "  FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_players (" +
                "  portal_id TEXT NOT NULL," +
                "  player_uuid TEXT NOT NULL," +
                "  access_type TEXT NOT NULL," +
                "  PRIMARY KEY (portal_id, player_uuid)," +
                "  FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_connections (" +
                "  portal1_id TEXT NOT NULL," +
                "  portal2_id TEXT NOT NULL," +
                "  PRIMARY KEY (portal1_id, portal2_id)" +
                ")"
            );
            getConnection().commit();
        }
    }

    @Override
    public void saveAll(List<Portal> portals, List<PortalConnection> connections) {
        try {
            Connection conn = getConnection();

            // Clear existing data (cascade deletes child rows automatically)
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM portal_connections");
                stmt.executeUpdate("DELETE FROM portal_players");
                stmt.executeUpdate("DELETE FROM portal_blocks");
                stmt.executeUpdate("DELETE FROM portals");
            }

            // Insert portals
            String insertPortal =
                "INSERT INTO portals (id, orientation, active, owner_uuid, access_mode, " +
                "lever_world, lever_x, lever_y, lever_z) VALUES (?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(insertPortal)) {
                for (Portal portal : portals) {
                    ps.setString(1, portal.getId());
                    ps.setString(2, portal.getOrientation().name());
                    ps.setBoolean(3, portal.isActive());
                    ps.setString(4, portal.getOwnerId() != null ? portal.getOwnerId().toString() : null);
                    ps.setString(5, portal.getAccessMode().name());

                    Location lever = portal.getLeverLocation();
                    if (lever != null && lever.getWorld() != null) {
                        ps.setString(6, lever.getWorld().getName());
                        ps.setInt(7, lever.getBlockX());
                        ps.setInt(8, lever.getBlockY());
                        ps.setInt(9, lever.getBlockZ());
                    } else {
                        ps.setNull(6, Types.VARCHAR);
                        ps.setNull(7, Types.INTEGER);
                        ps.setNull(8, Types.INTEGER);
                        ps.setNull(9, Types.INTEGER);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Insert blocks
            String insertBlock =
                "INSERT INTO portal_blocks (portal_id, world, x, y, z) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(insertBlock)) {
                for (Portal portal : portals) {
                    for (Location loc : portal.getBlockLocations()) {
                        if (loc.getWorld() == null) continue;
                        ps.setString(1, portal.getId());
                        ps.setString(2, loc.getWorld().getName());
                        ps.setInt(3, loc.getBlockX());
                        ps.setInt(4, loc.getBlockY());
                        ps.setInt(5, loc.getBlockZ());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            // Insert player permissions
            String insertPlayer =
                "INSERT INTO portal_players (portal_id, player_uuid, access_type) VALUES (?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(insertPlayer)) {
                for (Portal portal : portals) {
                    for (UUID uuid : portal.getAllowedPlayers()) {
                        ps.setString(1, portal.getId());
                        ps.setString(2, uuid.toString());
                        ps.setString(3, "ALLOW");
                        ps.addBatch();
                    }
                    for (UUID uuid : portal.getDeniedPlayers()) {
                        ps.setString(1, portal.getId());
                        ps.setString(2, uuid.toString());
                        ps.setString(3, "DENY");
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            // Insert connections
            String insertConn =
                "INSERT INTO portal_connections (portal1_id, portal2_id) VALUES (?,?)";
            try (PreparedStatement ps = conn.prepareStatement(insertConn)) {
                for (PortalConnection c : connections) {
                    ps.setString(1, c.getPortal1Id());
                    ps.setString(2, c.getPortal2Id());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            plugin.getLogger().info("[Storage] Saved " + portals.size() + " portals and "
                + connections.size() + " connections to PostgreSQL.");
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to save to PostgreSQL: " + e.getMessage());
            try { if (connection != null) connection.rollback(); } catch (SQLException ex) { /* ignore */ }
        }
    }

    @Override
    public List<Portal> loadPortals() {
        List<Portal> result = new ArrayList<>();
        try {
            try (Statement stmt = getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM portals")) {

                while (rs.next()) {
                    String id = rs.getString("id");
                    Portal.Orientation orientation;
                    try {
                        orientation = Portal.Orientation.valueOf(rs.getString("orientation"));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("[Storage] Invalid orientation for portal '"
                            + id + "', skipping.");
                        continue;
                    }

                    List<Location> blocks = loadBlocksForPortal(id);
                    if (blocks.isEmpty()) {
                        plugin.getLogger().warning("[Storage] Portal '" + id
                            + "' has no valid blocks, skipping.");
                        continue;
                    }

                    Portal portal = new Portal(id, orientation, blocks);
                    portal.setActive(rs.getBoolean("active"));

                    String ownerStr = rs.getString("owner_uuid");
                    if (ownerStr != null) {
                        try { portal.setOwnerId(UUID.fromString(ownerStr)); }
                        catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[Storage] Invalid owner UUID for portal '" + id + "'");
                        }
                    }

                    String accessModeStr = rs.getString("access_mode");
                    if (accessModeStr != null) {
                        try { portal.setAccessMode(Portal.AccessMode.valueOf(accessModeStr)); }
                        catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[Storage] Invalid access mode for portal '" + id + "'");
                        }
                    }

                    loadPlayerPermissions(portal);

                    String leverWorld = rs.getString("lever_world");
                    if (leverWorld != null) {
                        World world = Bukkit.getWorld(leverWorld);
                        if (world != null) {
                            portal.setLeverLocation(new Location(world,
                                rs.getInt("lever_x"),
                                rs.getInt("lever_y"),
                                rs.getInt("lever_z")));
                        }
                    }

                    result.add(portal);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to load portals from PostgreSQL: " + e.getMessage());
        }

        return result;
    }

    private List<Location> loadBlocksForPortal(String portalId) throws SQLException {
        List<Location> blocks = new ArrayList<>();
        String sql = "SELECT world, x, y, z FROM portal_blocks WHERE portal_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, portalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world == null) {
                        plugin.getLogger().warning("[Storage] World '"
                            + rs.getString("world") + "' not found for portal '" + portalId + "'");
                        continue;
                    }
                    blocks.add(new Location(world,
                        rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                }
            }
        }
        return blocks;
    }

    private void loadPlayerPermissions(Portal portal) throws SQLException {
        String sql = "SELECT player_uuid, access_type FROM portal_players WHERE portal_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, portal.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        String type = rs.getString("access_type");
                        if ("ALLOW".equals(type)) {
                            portal.allowPlayer(uuid);
                        } else if ("DENY".equals(type)) {
                            portal.denyPlayer(uuid);
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("[Storage] Invalid UUID in portal_players for portal '"
                            + portal.getId() + "'");
                    }
                }
            }
        }
    }

    @Override
    public List<PortalConnection> loadConnections() {
        List<PortalConnection> result = new ArrayList<>();
        try {
            try (Statement stmt = getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT portal1_id, portal2_id FROM portal_connections")) {
                while (rs.next()) {
                    result.add(new PortalConnection(
                        rs.getString("portal1_id"),
                        rs.getString("portal2_id")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to load connections from PostgreSQL: " + e.getMessage());
        }

        return result;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("[Storage] PostgreSQL connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().warning("[Storage] Error closing PostgreSQL connection: " + e.getMessage());
            }
        }
    }
}
