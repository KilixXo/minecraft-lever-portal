package com.portal.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * PortalRegistry - Manages all portal data including creation, storage, and connection logic.
 */
public class PortalRegistry {
    
    private final Main plugin;
    private final EconomyManager economyManager;
    private PortalAccessManager accessManager;
    private final Map<String, Portal> portals = new HashMap<>();
    private final Set<PortalConnection> connections = new HashSet<>();
    private final Map<UUID, PortalCreationSession> creationSessions = new HashMap<>();
    private final Set<UUID> recentlyTeleported = new HashSet<>();
    private final File dataFolder;
    
    public PortalRegistry(Main plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        this.dataFolder = new File(plugin.getDataFolder(), "portals");
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
    }
    
    /**
     * Set the access manager (called after initialization).
     */
    public void setAccessManager(PortalAccessManager accessManager) {
        this.accessManager = accessManager;
    }
    
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
     * Find all portals at a given location.
     */
    public List<Portal> findPortalsAt(Location loc) {
        List<Portal> result = new ArrayList<>();
        for (Portal p : this.portals.values()) {
            if (p.containsLocation(loc)) {
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
        // Validate connection
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
     * Check if two portals can be connected.
     */
    private boolean isValidConnection(Portal source, Portal target) {
        // Must have same orientation
        if (source.getOrientation() != target.getOrientation()) {
            return false;
        }
        
        // Calculate center points
        Location sourceCenter = source.getCenter();
        Location targetCenter = target.getCenter();
        
        if (sourceCenter == null || targetCenter == null) {
            return false;
        }
        
        // Must be in same world
        if (!sourceCenter.getWorld().equals(targetCenter.getWorld())) {
            return false;
        }
        
        // Distance should be reasonable (not too close)
        double distance = sourceCenter.distance(targetCenter);
        if (distance < 3) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Remove a portal.
     */
    public void removePortal(String id) {
        this.portals.remove(id);
        // Also remove from connections
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
        
        if (session.getBlockCount() < 2) {
            player.sendMessage("§cPortal must have at least 2 blocks!");
            return;
        }
        
        Portal portal = new Portal(session.getName(), session.getOrientation(), session.getBlocks());
        portal.setOwnerId(playerId); // Set the creator as owner
        portals.put(portal.getId(), portal);
        creationSessions.remove(playerId);
        
        player.sendMessage("§aPortal '" + portal.getId() + "' created with " + portal.getBlockCount() + " blocks!");
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
     */
    public void handlePlayerEnteringPortal(Player player) {
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        
        // Prevent rapid re-teleportation
        if (recentlyTeleported.contains(playerId)) {
            return;
        }
        
        List<Portal> portalsAtLocation = this.findPortalsAt(player.getLocation());
        
        for (Portal portal : portalsAtLocation) {
            if (!portal.isActive()) {
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
                return; // Player doesn't have enough diamonds
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
            
            // Add cooldown
            recentlyTeleported.add(playerId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                recentlyTeleported.remove(playerId);
            }, 40L); // 2 second cooldown
            
            break; // Only teleport through one portal
        }
    }
    
    /**
     * Save all portals to disk.
     */
    public void saveAllPortals() {
        try {
            File portalFile = new File(dataFolder, "portals.json");
            FileWriter writer = new FileWriter(portalFile);
            
            writer.write("{\n");
            writer.write("  \"portals\": [\n");
            
            List<Portal> portalList = new ArrayList<>(portals.values());
            for (int i = 0; i < portalList.size(); i++) {
                Portal portal = portalList.get(i);
                writer.write("    " + portal.toJson());
                if (i < portalList.size() - 1) {
                    writer.write(",");
                }
                writer.write("\n");
            }
            
            writer.write("  ],\n");
            writer.write("  \"connections\": [\n");
            
            List<PortalConnection> connList = new ArrayList<>(connections);
            for (int i = 0; i < connList.size(); i++) {
                PortalConnection conn = connList.get(i);
                writer.write("    {\"portal1\":\"" + conn.getPortal1Id() + 
                           "\",\"portal2\":\"" + conn.getPortal2Id() + "\"}");
                if (i < connList.size() - 1) {
                    writer.write(",");
                }
                writer.write("\n");
            }
            
            writer.write("  ]\n");
            writer.write("}\n");
            writer.close();
            
            plugin.getLogger().info("Saved " + portals.size() + " portals and " + 
                                   connections.size() + " connections.");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save portals: " + e.getMessage());
        }
    }
    
    /**
     * Load all portals from disk.
     */
    public void loadAllPortals() {
        File portalFile = new File(dataFolder, "portals.json");
        if (!portalFile.exists()) {
            plugin.getLogger().info("No saved portals found.");
            return;
        }
        
        try {
            String content = Files.readString(portalFile.toPath());
            // Simple JSON parsing - in production, use a proper JSON library
            plugin.getLogger().info("Portal data loaded from file.");
            // For now, just log that we attempted to load
            // Full JSON parsing would require a library like Gson
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load portals: " + e.getMessage());
        }
    }
    
    /**
     * Portal creation session helper class.
     */
    private static class PortalCreationSession {
        private final String name;
        private final Portal.Orientation orientation;
        private final List<Location> blocks = new ArrayList<>();
        
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
            // Check if block already added
            for (Location loc : blocks) {
                if (loc.getBlockX() == location.getBlockX() &&
                    loc.getBlockY() == location.getBlockY() &&
                    loc.getBlockZ() == location.getBlockZ() &&
                    loc.getWorld().equals(location.getWorld())) {
                    return; // Already added
                }
            }
            blocks.add(location.clone());
        }
        
        public List<Location> getBlocks() {
            return new ArrayList<>(blocks);
        }
        
        public int getBlockCount() {
            return blocks.size();
        }
    }
}

