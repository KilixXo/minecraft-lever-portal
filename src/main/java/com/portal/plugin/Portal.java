package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portal - Represents a single portal with its configuration.
 */
public class Portal {
    
    private final String id;
    private final Orientation orientation;
    private final List<Location> blockLocations;
    private boolean active = false;
    private Location leverLocation;
    
    // Access control fields
    private UUID ownerId;
    private AccessMode accessMode = AccessMode.PUBLIC;
    private final Set<UUID> allowedPlayers = new HashSet<>();
    private final Set<UUID> deniedPlayers = new HashSet<>();
    
    public enum Orientation { 
        VERTICAL, 
        HORIZONTAL 
    }
    
    public enum AccessMode {
        PUBLIC,    // Everyone can use
        PRIVATE,   // Only owner and allowed players
        WHITELIST, // Only explicitly allowed players
        BLACKLIST  // Everyone except denied players
    }
    
    public Portal(String id, Orientation orientation, List<Location> blockLocations) {
        this.id = id;
        this.orientation = orientation;
        this.blockLocations = new ArrayList<>(blockLocations);
    }
    
    public Portal(String id, Orientation orientation, List<Location> blockLocations, UUID ownerId) {
        this.id = id;
        this.orientation = orientation;
        this.blockLocations = new ArrayList<>(blockLocations);
        this.ownerId = ownerId;
    }
    
    public String getId() { 
        return this.id; 
    }
    
    public Orientation getOrientation() { 
        return this.orientation; 
    }
    
    public boolean isActive() { 
        return this.active; 
    }
    
    public void setActive(boolean active) { 
        this.active = active;
        updatePortalBlocks();
    }
    
    public List<Location> getBlockLocations() { 
        return new ArrayList<>(this.blockLocations); 
    }
    
    public Location getLeverLocation() {
        return leverLocation;
    }
    
    public void setLeverLocation(Location leverLocation) {
        this.leverLocation = leverLocation;
    }
    
    // Access control methods
    
    public UUID getOwnerId() {
        return ownerId;
    }
    
    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }
    
    public AccessMode getAccessMode() {
        return accessMode;
    }
    
    public void setAccessMode(AccessMode accessMode) {
        this.accessMode = accessMode;
    }
    
    public boolean isOwner(UUID playerId) {
        return ownerId != null && ownerId.equals(playerId);
    }
    
    public void allowPlayer(UUID playerId) {
        allowedPlayers.add(playerId);
        deniedPlayers.remove(playerId);
    }
    
    public void denyPlayer(UUID playerId) {
        deniedPlayers.add(playerId);
        allowedPlayers.remove(playerId);
    }
    
    public void removePlayerAccess(UUID playerId) {
        allowedPlayers.remove(playerId);
        deniedPlayers.remove(playerId);
    }
    
    public boolean isPlayerAllowed(UUID playerId) {
        return allowedPlayers.contains(playerId);
    }
    
    public boolean isPlayerDenied(UUID playerId) {
        return deniedPlayers.contains(playerId);
    }
    
    public Set<UUID> getAllowedPlayers() {
        return new HashSet<>(allowedPlayers);
    }
    
    public Set<UUID> getDeniedPlayers() {
        return new HashSet<>(deniedPlayers);
    }
    
    /**
     * Check if a player has access to use this portal.
     */
    public boolean canPlayerUse(UUID playerId) {
        // Owner always has access
        if (isOwner(playerId)) {
            return true;
        }
        
        switch (accessMode) {
            case PUBLIC:
                // Everyone can use unless explicitly denied
                return !deniedPlayers.contains(playerId);
                
            case PRIVATE:
                // Only owner and allowed players
                return allowedPlayers.contains(playerId);
                
            case WHITELIST:
                // Only explicitly allowed players
                return allowedPlayers.contains(playerId);
                
            case BLACKLIST:
                // Everyone except denied players
                return !deniedPlayers.contains(playerId);
                
            default:
                return false;
        }
    }
    
    /**
     * Get the center location of this portal.
     */
    public Location getCenter() {
        if (blockLocations.isEmpty()) {
            return null;
        }
        
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        
        World world = blockLocations.get(0).getWorld();
        
        for (Location loc : this.blockLocations) {
            minX = Math.min(minX, loc.getX());
            maxX = Math.max(maxX, loc.getX());
            minY = Math.min(minY, loc.getY());
            maxY = Math.max(maxY, loc.getY());
            minZ = Math.min(minZ, loc.getZ());
            maxZ = Math.max(maxZ, loc.getZ());
        }
        
        return new Location(world,
                           (minX + maxX) / 2.0,
                           (minY + maxY) / 2.0,
                           (minZ + maxZ) / 2.0);
    }
    
    /**
     * Check if a player is inside this portal.
     */
    public boolean containsPlayer(Player player) {
        Location playerLoc = player.getLocation();
        for (Location loc : this.blockLocations) {
            if (loc.distance(playerLoc) < 1.5) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a location is part of this portal.
     */
    public boolean containsLocation(Location location) {
        for (Location loc : this.blockLocations) {
            if (loc.getBlockX() == location.getBlockX() &&
                loc.getBlockY() == location.getBlockY() &&
                loc.getBlockZ() == location.getBlockZ() &&
                loc.getWorld().equals(location.getWorld())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Update portal blocks visual state based on active status.
     */
    private void updatePortalBlocks() {
        for (Location loc : blockLocations) {
            Block block = loc.getBlock();
            if (active) {
                // Make portal blocks glow when active (using light-emitting blocks)
                if (block.getType() != Material.AIR && block.getType() != Material.LEVER) {
                    // Portal is active - could add particle effects here
                }
            }
        }
    }
    
    /**
     * Add a block location to this portal.
     */
    public void addBlockLocation(Location location) {
        if (!containsLocation(location)) {
            blockLocations.add(location.clone());
        }
    }
    
    /**
     * Get the number of blocks in this portal.
     */
    public int getBlockCount() {
        return blockLocations.size();
    }
    
    /**
     * Convert portal to JSON string for saving.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(this.id).append("\",");
        sb.append("\"orientation\":\"").append(this.orientation.name()).append("\",");
        sb.append("\"active\":").append(this.active).append(",");
        
        // Access control data
        if (ownerId != null) {
            sb.append("\"owner\":\"").append(ownerId.toString()).append("\",");
        }
        sb.append("\"accessMode\":\"").append(accessMode.name()).append("\",");
        
        // Allowed players
        sb.append("\"allowedPlayers\":[");
        int idx = 0;
        for (UUID uuid : allowedPlayers) {
            if (idx > 0) sb.append(",");
            sb.append("\"").append(uuid.toString()).append("\"");
            idx++;
        }
        sb.append("],");
        
        // Denied players
        sb.append("\"deniedPlayers\":[");
        idx = 0;
        for (UUID uuid : deniedPlayers) {
            if (idx > 0) sb.append(",");
            sb.append("\"").append(uuid.toString()).append("\"");
            idx++;
        }
        sb.append("],");
        
        if (leverLocation != null) {
            sb.append("\"lever\":{");
            sb.append("\"world\":\"").append(leverLocation.getWorld().getName()).append("\",");
            sb.append("\"x\":").append(leverLocation.getBlockX()).append(",");
            sb.append("\"y\":").append(leverLocation.getBlockY()).append(",");
            sb.append("\"z\":").append(leverLocation.getBlockZ());
            sb.append("},");
        }
        
        sb.append("\"blocks\":[");
        for (int i = 0; i < blockLocations.size(); i++) {
            Location loc = blockLocations.get(i);
            sb.append("{");
            sb.append("\"world\":\"").append(loc.getWorld().getName()).append("\",");
            sb.append("\"x\":").append(loc.getBlockX()).append(",");
            sb.append("\"y\":").append(loc.getBlockY()).append(",");
            sb.append("\"z\":").append(loc.getBlockZ());
            sb.append("}");
            if (i < blockLocations.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return "Portal{" +
               "id='" + this.id + '\'' +
               ", orientation=" + this.orientation +
               ", active=" + this.active +
               ", blocks=" + blockLocations.size() +
               '}';
    }
}
