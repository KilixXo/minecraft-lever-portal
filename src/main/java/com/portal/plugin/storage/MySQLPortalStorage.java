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
 * MySQLPortalStorage — persists portal data in a MySQL or MariaDB database.
 *
 * <p>Both MySQL (driver: {@code com.mysql.cj.jdbc.Driver}) and MariaDB
 * (driver: {@code org.mariadb.jdbc.Driver}) are supported; the correct driver
 * is selected automatically based on {@code storage.type} in {@code config.yml}.
 *
 * <p>PERF-04 fix: {@link #getConnection()} validates the connection before each
 * operation and reconnects automatically if the connection has timed out or been
 * closed by the server (e.g., after the MySQL default 8-hour idle timeout).
 *
 * <p>Schema (identical to the SQLite schema, using standard SQL):
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
 */
public class MySQLPortalStorage implements PortalStorage {

    private final Main plugin;
    private final DatabaseConfig dbConfig;
    private Connection connection;

    public MySQLPortalStorage(Main plugin, DatabaseConfig dbConfig) {
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
            plugin.getLogger().info("[Storage] Using " + dbConfig.getType().name()
                + " backend: " + dbConfig.getHost() + ":" + dbConfig.getPort()
                + "/" + dbConfig.getDatabase());
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("[Storage] JDBC driver not found for "
                + dbConfig.getType().name() + ": " + e.getMessage());
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to connect to "
                + dbConfig.getType().name() + " database: " + e.getMessage());
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
            plugin.getLogger().info("[Storage] " + dbConfig.getType().name()
                + " connection lost — reconnecting...");
            openConnection();
        }
        return connection;
    }

    private void createTables() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portals (" +
                "  id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "  orientation VARCHAR(32) NOT NULL," +
                "  active TINYINT(1) NOT NULL DEFAULT 0," +
                "  owner_uuid VARCHAR(36)," +
                "  access_mode VARCHAR(32) NOT NULL DEFAULT 'PUBLIC'," +
                "  lever_world VARCHAR(255)," +
                "  lever_x INT," +
                "  lever_y INT," +
                "  lever_z INT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_blocks (" +
                "  portal_id VARCHAR(255) NOT NULL," +
                "  world VARCHAR(255) NOT NULL," +
                "  x INT NOT NULL," +
                "  y INT NOT NULL," +
                "  z INT NOT NULL," +
                "  PRIMARY KEY (portal_id, world, x, y, z)," +
                "  FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_players (" +
                "  portal_id VARCHAR(255) NOT NULL," +
                "  player_uuid VARCHAR(36) NOT NULL," +
                "  access_type VARCHAR(8) NOT NULL," +
                "  PRIMARY KEY (portal_id, player_uuid)," +
                "  FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS portal_connections (" +
                "  portal1_id VARCHAR(255) NOT NULL," +
                "  portal2_id VARCHAR(255) NOT NULL," +
                "  PRIMARY KEY (portal1_id, portal2_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
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
                    ps.setInt(3, portal.isActive() ? 1 : 0);
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
                + connections.size() + " connections to " + dbConfig.getType().name() + ".");
        } catch (SQLException e) {
            plugin.getLogger().severe("[Storage] Failed to save to "
                + dbConfig.getType().name() + ": " + e.getMessage());
            try { if (connection != null) connection.rollback(); } catch (SQLException ex) { /* ignore */ }
        }
    }

    @Override
    public List<Portal> loadPortals() {
        List<Portal> result = new ArrayList<>();
        try {
            Connection conn = getConnection();
            try (Statement stmt = conn.createStatement();
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
                    portal.setActive(rs.getInt("active") == 1);

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
            plugin.getLogger().severe("[Storage] Failed to load portals from "
                + dbConfig.getType().name() + ": " + e.getMessage());
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
            plugin.getLogger().severe("[Storage] Failed to load connections from "
                + dbConfig.getType().name() + ": " + e.getMessage());
        }

        return result;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("[Storage] " + dbConfig.getType().name()
                    + " connection closed.");
            } catch (SQLException e) {
                plugin.getLogger().warning("[Storage] Error closing "
                    + dbConfig.getType().name() + " connection: " + e.getMessage());
            }
        }
    }
}
