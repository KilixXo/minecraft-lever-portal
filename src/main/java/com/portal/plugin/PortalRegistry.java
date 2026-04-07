package com.portal.plugin;

import com.portal.plugin.storage.DatabaseConfig;
import com.portal.plugin.storage.JsonPortalStorage;
import com.portal.plugin.storage.MySQLPortalStorage;
import com.portal.plugin.storage.PostgreSQLPortalStorage;
import com.portal.plugin.storage.PortalStorage;
import com.portal.plugin.storage.SQLitePortalStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PortalRegistry - Manages all portal data including creation, storage, and connection logic.
 */
public class PortalRegistry {

    // ── Configurable constants — read from config.yml (MAINT-01 fix) ──
    // Defaults are used if the config key is absent.
    private int teleportCooldownTicks;
    private int minPortalBlocks;
    private int maxPortalBlocks;
    private double minLinkDistance;

    // Keep public static accessors for backward compatibility (e.g., LeverHandler)
    public int getTeleportCooldownTicks() { return teleportCooldownTicks; }
    public int getMinPortalBlocks()       { return minPortalBlocks; }
    public int getMaxPortalBlocks()       { return maxPortalBlocks; }
    public double getMinLinkDistance()    { return minLinkDistance; }

    private final Main plugin;
    private final EconomyManager economyManager;
    private PortalAccessManager accessManager;
    private PortalStorage storage;

    // SEC-05 fix: use ConcurrentHashMap for thread-safe access
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    private final Set<PortalConnection> connections = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PortalCreationSession> creationSessions = new ConcurrentHashMap<>();
    private final Set<UUID> recentlyTeleported = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastPortalUsed = new ConcurrentHashMap<>();

    // ── Spatial index for fast portal lookup (M-2) — also ConcurrentHashMap ──
    private final Map<String, Set<Portal>> portalsByChunk = new ConcurrentHashMap<>();

    public PortalRegistry(Main plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        loadConstants();
    }

    /**
     * MAINT-01 fix: load configurable constants from config.yml instead of hardcoding.
     */
    private void loadConstants() {
        teleportCooldownTicks = plugin.getConfig().getInt("portal.teleport_cooldown_ticks", 40);
        minPortalBlocks       = plugin.getConfig().getInt("portal.min_blocks", 2);
        maxPortalBlocks       = plugin.getConfig().getInt("portal.max_blocks", 100);
        minLinkDistance       = plugin.getConfig().getDouble("portal.min_link_distance", 3.0);
    }

    /**
     * Initialise the storage backend based on config.yml {@code storage.type}.
     * Must be called before loadAllPortals() / saveAllPortals().
     *
     * <p>Supported values for {@code storage.type}:
     * <ul>
     *   <li>{@code json}       — JSON file (default, no extra setup)</li>
     *   <li>{@code sqlite}     — local SQLite file</li>
     *   <li>{@code mysql}      — remote MySQL server</li>
     *   <li>{@code mariadb}    — remote MariaDB server</li>
     *   <li>{@code postgresql} — remote PostgreSQL server</li>
     * </ul>
     */
    public void initStorage() {
        DatabaseConfig dbConfig = new DatabaseConfig(plugin);
        switch (dbConfig.getType()) {
            case SQLITE:
                this.storage = new SQLitePortalStorage(plugin);
                break;
            case MYSQL:
            case MARIADB:
                this.storage = new MySQLPortalStorage(plugin, dbConfig);
                break;
            case POSTGRESQL:
                this.storage = new PostgreSQLPortalStorage(plugin, dbConfig);
                break;
            default:
                this.storage = new JsonPortalStorage(plugin);
                break;
        }
        this.storage.init();
    }

    /**
     * Close the storage backend. Call on plugin disable.
     */
    public void closeStorage() {
        if (storage != null) {
            storage.close();
        }
    }

    /**
     * Set the access manager (called after initialization).
     */
    public void setAccessManager(PortalAccessManager accessManager) {
        this.accessManager = accessManager;
    }

    // ── Spatial index helpers ──

    private static String chunkKey(Location loc) {
        World w = loc.getWorld();
        String worldName = (w != null) ? w.getName() : "unknown";
        return worldName + "," + (loc.getBlockX() >> 4) + "," + (loc.getBlockZ() >> 4);
    }

    /**
     * Index a portal by both its frame blocks AND its interior locations.
     * Interior locations are what the player actually walks through.
     */
    private void indexPortal(Portal portal) {
        // Index frame blocks
        for (Location loc : portal.getBlockLocations()) {
            String key = chunkKey(loc);
            portalsByChunk.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(portal);
        }
        // Index interior locations (the passable space inside the frame)
        for (Location loc : portal.getInteriorLocations()) {
            String key = chunkKey(loc);
            portalsByChunk.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(portal);
        }
    }

    private void unindexPortal(Portal portal) {
        // Unindex frame blocks
        for (Location loc : portal.getBlockLocations()) {
            String key = chunkKey(loc);
            Set<Portal> set = portalsByChunk.get(key);
            if (set != null) {
                set.remove(portal);
                if (set.isEmpty()) {
                    portalsByChunk.remove(key);
                }
            }
        }
        // Unindex interior locations
        for (Location loc : portal.getInteriorLocations()) {
            String key = chunkKey(loc);
            Set<Portal> set = portalsByChunk.get(key);
            if (set != null) {
                set.remove(portal);
                if (set.isEmpty()) {
                    portalsByChunk.remove(key);
                }
            }
        }
    }

    // ── Public API ──

    /**
     * Start a portal creation session for a player.
     */
    public void startPortalCreation(Player player, String name, Portal.Orientation orientation) {
        if (portals.containsKey(name)) {
            player.sendMessage("§cA portal with this name already exists!");
            return;
        }

        PortalCreationSession session = new PortalCreationSession(name, orientation);
        creationSessions.put(player.getUniqueId(), session);
    }

    /**
     * Get a portal by name.
     */
    public Portal getPortal(String name) {
        return portals.get(name);
    }

    /**
     * Get all portal names.
     */
    public List<String> getAllPortalNames() {
        return new ArrayList<>(portals.keySet());
    }

    /**
     * Find all portals at a given location (chunk-indexed O(1) lookup).
     * Checks both frame blocks and interior locations so players are detected
     * when walking through the portal opening.
     */
    public List<Portal> findPortalsAt(Location loc) {
        List<Portal> result = new ArrayList<>();
        String key = chunkKey(loc);
        Set<Portal> candidates = portalsByChunk.get(key);
        if (candidates != null) {
            for (Portal p : candidates) {
                // Match on interior (passable space) OR frame blocks
                if (p.isInteriorLocation(loc) || p.containsLocation(loc)) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    /**
     * PERF-01 fix: Find portals whose center is within {@code radius} blocks of {@code loc}.
     * Uses the chunk spatial index to avoid iterating all portals.
     *
     * @param loc    center of the search area
     * @param radius maximum distance from portal center to loc
     * @return list of matching portals (may be empty, never null)
     */
    public List<Portal> findPortalsNear(Location loc, double radius) {
        Set<Portal> candidates = new HashSet<>();
        World locWorld = loc.getWorld();
        if (locWorld == null) return new ArrayList<>();

        // Collect candidates from nearby chunks
        int chunkRadius = (int) Math.ceil(radius / 16.0) + 1;
        int centerChunkX = loc.getBlockX() >> 4;
        int centerChunkZ = loc.getBlockZ() >> 4;
        String worldName = locWorld.getName();

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                String key = worldName + "," + cx + "," + cz;
                Set<Portal> chunk = portalsByChunk.get(key);
                if (chunk != null) {
                    candidates.addAll(chunk);
                }
            }
        }

        // Filter by actual distance
        List<Portal> result = new ArrayList<>();
        for (Portal p : candidates) {
            Location center = p.getCenter();
            if (center == null) continue;
            World pWorld = center.getWorld();
            if (pWorld == null || !pWorld.equals(locWorld)) continue;
            if (center.distance(loc) <= radius) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Find connected portal ID.
     */
    public String findConnectedPortalId(Portal source) {
        for (PortalConnection conn : this.connections) {
            if (conn.involves(source.getId())) {
                return conn.getOtherPortal(source.getId());
            }
        }
        return null;
    }

    /**
     * Connect two portals together.
     */
    public boolean connectPortals(Portal source, Portal target) {
        if (!isValidConnection(source, target)) {
            return false;
        }

        // Remove any existing connections for these portals
        connections.removeIf(conn -> conn.involves(source.getId()) || conn.involves(target.getId()));

        // Create new connection
        PortalConnection conn = new PortalConnection(source.getId(), target.getId());
        this.connections.add(conn);
        return true;
    }

    /**
     * Check if two portals can be connected (null-safe world check).
     */
    private boolean isValidConnection(Portal source, Portal target) {
        // Must have same orientation
        if (source.getOrientation() != target.getOrientation()) {
            return false;
        }

        Location sourceCenter = source.getCenter();
        Location targetCenter = target.getCenter();
        if (sourceCenter == null || targetCenter == null) {
            return false;
        }

        // Null-safe world comparison
        World srcWorld = sourceCenter.getWorld();
        World tgtWorld = targetCenter.getWorld();
        if (srcWorld == null || tgtWorld == null || !srcWorld.equals(tgtWorld)) {
            return false;
        }

        // Distance should be reasonable (not too close)
        double distance = sourceCenter.distance(targetCenter);
        return distance >= minLinkDistance;
    }

    /**
     * Remove a portal.
     */
    public void removePortal(String id) {
        Portal portal = this.portals.remove(id);
        if (portal != null) {
            unindexPortal(portal);
        }
        this.connections.removeIf(conn -> conn.involves(id));
    }

    /**
     * Handle player interaction with portals.
     */
    public void handlePortalInteraction(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if player is in a creation session
        if (creationSessions.containsKey(playerId)) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                PortalCreationSession session = creationSessions.get(playerId);
                Block clicked = event.getClickedBlock();

                // Don't add air or lever blocks
                if (clicked.getType() != Material.AIR && clicked.getType() != Material.LEVER) {
                    // Enforce block limit
                    if (session.getBlockCount() >= maxPortalBlocks) {
                        player.sendMessage("§cPortal cannot have more than " + maxPortalBlocks + " blocks!");
                        event.setCancelled(true);
                        return;
                    }
                    session.addBlock(clicked.getLocation());
                    player.sendMessage("§aBlock added to portal. Total blocks: " + session.getBlockCount());
                    player.sendMessage("§eUse §6/portal finish §eto complete the portal.");
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Finish portal creation for a player.
     */
    public void finishPortalCreation(Player player) {
        UUID playerId = player.getUniqueId();
        PortalCreationSession session = creationSessions.get(playerId);

        if (session == null) {
            player.sendMessage("§cYou don't have an active portal creation session!");
            return;
        }

        if (session.getBlockCount() < minPortalBlocks) {
            player.sendMessage("§cPortal must have at least " + minPortalBlocks + " blocks!");
            return;
        }

        Portal portal = new Portal(session.getName(), session.getOrientation(), session.getBlocks());
        portal.setOwnerId(playerId);
        portals.put(portal.getId(), portal);
        indexPortal(portal);
        creationSessions.remove(playerId);

        int interiorCount = portal.getInteriorBlockCount();
        player.sendMessage("§aPortal '" + portal.getId() + "' created with " + portal.getBlockCount() + " frame blocks!");
        if (interiorCount > 0) {
            player.sendMessage("§aPortal interior: " + interiorCount + " passable block(s) detected. Players will teleport when walking through.");
        } else {
            player.sendMessage("§eWarning: No interior space detected. Make sure your frame encloses an open area.");
        }
        player.sendMessage("§eYou are the owner of this portal. Use §6/portal access §eto manage permissions.");
        player.sendMessage("§eUse §6/portal link <portal1> <portal2> §eto connect portals.");
    }

    /**
     * Cancel portal creation for a player.
     */
    public void cancelPortalCreation(Player player) {
        UUID playerId = player.getUniqueId();
        if (creationSessions.remove(playerId) != null) {
            player.sendMessage("§ePortal creation cancelled.");
        } else {
            player.sendMessage("§cYou don't have an active portal creation session!");
        }
    }

    /**
     * Handle player entering a portal.
     *
     * BUG-02 fix: when the economy charge fails, we no longer use a bare {@code return}
     * that bypassed the cooldown guard. Instead we set a brief cooldown to prevent
     * the "insufficient funds" message from firing on every block-move event while
     * the player stands inside the portal.
     */
    public void handlePlayerEnteringPortal(Player player) {
        if (player == null) return;

        UUID playerId = player.getUniqueId();

        // Prevent rapid re-teleportation (atomic check-and-set)
        if (!recentlyTeleported.add(playerId)) {
            return; // Already on cooldown
        }

        boolean teleported = false;
        try {
            List<Portal> portalsAtLocation = this.findPortalsAt(player.getLocation());

            for (Portal portal : portalsAtLocation) {
                if (!portal.isActive()) {
                    continue;
                }

                // Check access control
                if (accessManager != null && !accessManager.canPlayerUsePortal(player, portal)) {
                    player.sendMessage(plugin.getConfig().getString("messages.access_denied",
                        "§cYou don't have permission to use this portal!"));
                    continue;
                }

                // Prevent teleport loops — skip portal we just came from
                String lastPortal = lastPortalUsed.get(playerId);
                if (portal.getId().equals(lastPortal)) {
                    continue;
                }

                // Find connected portal
                String connectedId = this.findConnectedPortalId(portal);
                if (connectedId == null) {
                    continue;
                }

                // Teleport player to connected portal
                Portal connectedPortal = this.portals.get(connectedId);
                if (connectedPortal == null || !connectedPortal.isActive()) {
                    continue;
                }

                // Check and charge for teleportation
                if (!economyManager.chargeTeleport(player, portal.getId())) {
                    // BUG-02 fix: apply a brief cooldown so the message is not spammed
                    // on every PlayerMoveEvent while the player stands in the portal.
                    teleported = true; // prevents immediate cooldown removal in finally
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        recentlyTeleported.remove(playerId);
                    }, 20L); // 1-second cooldown before next charge attempt
                    break;
                }

                Location targetLocation = connectedPortal.getCenter();
                if (targetLocation == null) {
                    continue;
                }

                // Copy player's yaw and pitch
                targetLocation.setYaw(player.getLocation().getYaw());
                targetLocation.setPitch(player.getLocation().getPitch());

                // Teleport
                player.teleport(targetLocation);
                player.sendMessage("§aTeleported through portal!");
                teleported = true;

                // Track last portal used to prevent loops
                lastPortalUsed.put(playerId, connectedId);

                // Schedule cooldown removal
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    recentlyTeleported.remove(playerId);
                    lastPortalUsed.remove(playerId);
                }, teleportCooldownTicks);

                break; // Only teleport through one portal
            }
        } finally {
            // Only remove from cooldown immediately if no teleport happened
            // (and no charge-failure cooldown was scheduled)
            if (!teleported) {
                recentlyTeleported.remove(playerId);
            }
        }
    }

    // ── Persistence — delegates to the configured PortalStorage backend ──

    /**
     * Save all portals and connections via the active storage backend.
     */
    public void saveAllPortals() {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialised — cannot save portals.");
            return;
        }
        storage.saveAll(new ArrayList<>(portals.values()), new ArrayList<>(connections));
    }

    /**
     * Load all portals and connections from the active storage backend.
     */
    public void loadAllPortals() {
        if (storage == null) {
            plugin.getLogger().warning("Storage not initialised — cannot load portals.");
            return;
        }

        List<Portal> loaded = storage.loadPortals();
        for (Portal portal : loaded) {
            portals.put(portal.getId(), portal);
            indexPortal(portal);
        }

        List<PortalConnection> loadedConns = storage.loadConnections();
        for (PortalConnection conn : loadedConns) {
            if (portals.containsKey(conn.getPortal1Id()) && portals.containsKey(conn.getPortal2Id())) {
                connections.add(conn);
            } else {
                plugin.getLogger().warning("Skipping connection " + conn.getPortal1Id() +
                    " <-> " + conn.getPortal2Id() + " (portal not found).");
            }
        }

        plugin.getLogger().info("Loaded " + portals.size() + " portals and " + connections.size() + " connections.");
    }

    /**
     * Get loaded lever locations for LeverHandler to restore associations.
     */
    public Map<String, Location> getLeverAssociations() {
        Map<String, Location> result = new HashMap<>();
        for (Map.Entry<String, Portal> entry : portals.entrySet()) {
            Location leverLoc = entry.getValue().getLeverLocation();
            if (leverLoc != null) {
                result.put(entry.getKey(), leverLoc);
            }
        }
        return result;
    }

    /**
     * Portal creation session helper class.
     *
     * BUG-03 fix: uses a HashSet for O(1) duplicate detection instead of O(n) linear scan.
     */
    private static class PortalCreationSession {
        private final String name;
        private final Portal.Orientation orientation;
        private final List<Location> blocks = new ArrayList<>();
        // BUG-03 fix: HashSet for O(1) duplicate detection
        private final Set<String> blockKeys = new HashSet<>();

        public PortalCreationSession(String name, Portal.Orientation orientation) {
            this.name = name;
            this.orientation = orientation;
        }

        public String getName() {
            return name;
        }

        public Portal.Orientation getOrientation() {
            return orientation;
        }

        public void addBlock(Location location) {
            // BUG-03 fix: O(1) duplicate check using string key
            String key = locationKey(location);
            if (blockKeys.add(key)) {
                blocks.add(location.clone());
            }
        }

        private static String locationKey(Location loc) {
            World w = loc.getWorld();
            String worldName = (w != null) ? w.getName() : "unknown";
            return worldName + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        }

        public List<Location> getBlocks() {
            return new ArrayList<>(blocks);
        }

        public int getBlockCount() {
            return blocks.size();
        }
    }
}
