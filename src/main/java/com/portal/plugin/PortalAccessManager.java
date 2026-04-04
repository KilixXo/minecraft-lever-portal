package com.portal.plugin;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * PortalAccessManager - Manages portal access permissions and entity filtering.
 */
public class PortalAccessManager {
    
    private final Main plugin;
    private final Set<EntityType> allowedEntityTypes = new HashSet<>();
    private boolean entityFilteringEnabled = false;
    
    public PortalAccessManager(Main plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }
    
    /**
     * Load configuration from config.yml
     */
    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfig();
        
        // Load entity filtering settings
        entityFilteringEnabled = config.getBoolean("access_control.entity_filtering.enabled", false);
        
        // Load allowed entity types
        allowedEntityTypes.clear();
        List<String> entityList = config.getStringList("access_control.entity_filtering.allowed_entities");
        for (String entityName : entityList) {
            try {
                EntityType type = EntityType.valueOf(entityName.toUpperCase());
                allowedEntityTypes.add(type);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid entity type in config: " + entityName);
            }
        }
        
        // If no entities specified, allow players by default
        if (allowedEntityTypes.isEmpty()) {
            allowedEntityTypes.add(EntityType.PLAYER);
        }
    }
    
    /**
     * Check if an entity type is allowed to use portals.
     */
    public boolean isEntityTypeAllowed(EntityType type) {
        if (!entityFilteringEnabled) {
            return true; // If filtering is disabled, allow all
        }
        return allowedEntityTypes.contains(type);
    }
    
    /**
     * Check if a player can use a specific portal.
     */
    public boolean canPlayerUsePortal(Player player, Portal portal) {
        UUID playerId = player.getUniqueId();
        
        // Admins can always use portals
        if (player.hasPermission("leverportal.admin")) {
            return true;
        }
        
        // Check entity type filtering
        if (!isEntityTypeAllowed(EntityType.PLAYER)) {
            return false;
        }
        
        // Check portal-specific permissions
        return portal.canPlayerUse(playerId);
    }
    
    /**
     * Set portal access mode.
     */
    public void setPortalAccessMode(Portal portal, Portal.AccessMode mode) {
        portal.setAccessMode(mode);
    }
    
    /**
     * Allow a player to use a portal.
     */
    public void allowPlayer(Portal portal, UUID playerId) {
        portal.allowPlayer(playerId);
    }
    
    /**
     * Deny a player from using a portal.
     */
    public void denyPlayer(Portal portal, UUID playerId) {
        portal.denyPlayer(playerId);
    }
    
    /**
     * Remove player access (neither allowed nor denied).
     */
    public void removePlayerAccess(Portal portal, UUID playerId) {
        portal.removePlayerAccess(playerId);
    }
    
    /**
     * Get list of allowed players for a portal.
     */
    public Set<UUID> getAllowedPlayers(Portal portal) {
        return portal.getAllowedPlayers();
    }
    
    /**
     * Get list of denied players for a portal.
     */
    public Set<UUID> getDeniedPlayers(Portal portal) {
        return portal.getDeniedPlayers();
    }
    
    /**
     * Check if a player is the owner of a portal.
     */
    public boolean isOwner(Portal portal, UUID playerId) {
        return portal.isOwner(playerId);
    }
    
    /**
     * Transfer portal ownership to another player.
     */
    public void transferOwnership(Portal portal, UUID newOwnerId) {
        portal.setOwnerId(newOwnerId);
    }
    
    /**
     * Get the owner ID of a portal.
     */
    public UUID getOwnerId(Portal portal) {
        return portal.getOwnerId();
    }
    
    /**
     * Get the access mode of a portal.
     */
    public Portal.AccessMode getAccessMode(Portal portal) {
        return portal.getAccessMode();
    }
    
    /**
     * Get allowed entity types.
     */
    public Set<EntityType> getAllowedEntityTypes() {
        return new HashSet<>(allowedEntityTypes);
    }
    
    /**
     * Check if entity filtering is enabled.
     */
    public boolean isEntityFilteringEnabled() {
        return entityFilteringEnabled;
    }
    
    /**
     * Set entity filtering enabled/disabled.
     */
    public void setEntityFilteringEnabled(boolean enabled) {
        this.entityFilteringEnabled = enabled;
        plugin.getConfig().set("access_control.entity_filtering.enabled", enabled);
        plugin.saveConfig();
    }
    
    /**
     * Add an allowed entity type.
     */
    public void addAllowedEntityType(EntityType type) {
        allowedEntityTypes.add(type);
        saveEntityTypes();
    }
    
    /**
     * Remove an allowed entity type.
     */
    public void removeAllowedEntityType(EntityType type) {
        allowedEntityTypes.remove(type);
        saveEntityTypes();
    }
    
    /**
     * Save entity types to config.
     */
    private void saveEntityTypes() {
        List<String> entityNames = new ArrayList<>();
        for (EntityType type : allowedEntityTypes) {
            entityNames.add(type.name());
        }
        plugin.getConfig().set("access_control.entity_filtering.allowed_entities", entityNames);
        plugin.saveConfig();
    }
}
