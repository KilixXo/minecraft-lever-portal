package com.portal.plugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PortalAccessManager - Manages portal access permissions and entity filtering.
 */
public class PortalAccessManager {
    
    private final Main plugin;
    private final Set<EntityType> allowedEntityTypes = new HashSet<>();
    private boolean entityFilteringEnabled = false;

    // SEC-04: runtime entity-filtering overrides live in access-data.yml, not config.yml
    private final File accessDataFile;
    
    public PortalAccessManager(Main plugin) {
        this.plugin = plugin;
        this.accessDataFile = new File(plugin.getDataFolder(), "access-data.yml");
        loadConfiguration();
    }
    
    /**
     * Load configuration from config.yml (static defaults) and then apply
     * runtime overrides from access-data.yml (SEC-04: runtime state is kept
     * separate from static config so config.yml is never rewritten at runtime).
     */
    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfig();
        
        // --- Static defaults from config.yml ---
        entityFilteringEnabled = config.getBoolean("access_control.entity_filtering.enabled", false);
        
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
        
        if (allowedEntityTypes.isEmpty()) {
            allowedEntityTypes.add(EntityType.PLAYER);
        }

        // --- Runtime overrides from access-data.yml (takes precedence over config.yml) ---
        if (accessDataFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(accessDataFile);
            if (data.contains("entity_filtering.enabled")) {
                entityFilteringEnabled = data.getBoolean("entity_filtering.enabled");
            }
            List<String> runtimeEntities = data.getStringList("entity_filtering.allowed_entities");
            if (!runtimeEntities.isEmpty()) {
                allowedEntityTypes.clear();
                for (String entityName : runtimeEntities) {
                    try {
                        allowedEntityTypes.add(EntityType.valueOf(entityName.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("[AccessManager] Invalid entity type in access-data.yml: " + entityName);
                    }
                }
                if (allowedEntityTypes.isEmpty()) {
                    allowedEntityTypes.add(EntityType.PLAYER);
                }
            }
        }
    }

    /**
     * Persist runtime entity-filtering state to access-data.yml.
     *
     * Called whenever {@link #setEntityFilteringEnabled}, {@link #addAllowedEntityType},
     * or {@link #removeAllowedEntityType} mutates in-memory state, so changes
     * survive a server restart without touching config.yml.
     */
    private void saveEntityData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("entity_filtering.enabled", entityFilteringEnabled);
        List<String> names = new ArrayList<>();
        for (EntityType type : allowedEntityTypes) {
            names.add(type.name());
        }
        data.set("entity_filtering.allowed_entities", names);
        try {
            data.save(accessDataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[AccessManager] Failed to save access-data.yml: " + e.getMessage());
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
     *
     * MAINT-05 fix: removed the redundant {@code isEntityTypeAllowed(EntityType.PLAYER)}
     * check. Players are always of type PLAYER; if the admin has removed PLAYER from the
     * allowed entity list that is a misconfiguration that would block all players entirely.
     * The entity filtering feature is intended for non-player entities (future Paper API
     * EntityMoveEvent support), not for gating player access — that is handled by the
     * portal's own access mode and allowed/denied lists.
     */
    public boolean canPlayerUsePortal(Player player, Portal portal) {
        UUID playerId = player.getUniqueId();
        
        // Admins can always use portals
        if (player.hasPermission("leverportal.admin")) {
            return true;
        }
        
        // Check portal-specific permissions (access mode, whitelist, blacklist, etc.)
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
     *
     * FIX-10 (revised): runtime state is persisted to access-data.yml so that
     * admin changes survive a server restart without rewriting config.yml.
     */
    public void setEntityFilteringEnabled(boolean enabled) {
        this.entityFilteringEnabled = enabled;
        saveEntityData();
    }
    
    /**
     * Add an allowed entity type.
     *
     * FIX-10 (revised): persisted to access-data.yml — see {@link #setEntityFilteringEnabled}.
     */
    public void addAllowedEntityType(EntityType type) {
        allowedEntityTypes.add(type);
        saveEntityData();
    }
    
    /**
     * Remove an allowed entity type.
     *
     * FIX-10 (revised): persisted to access-data.yml — see {@link #setEntityFilteringEnabled}.
     */
    public void removeAllowedEntityType(EntityType type) {
        allowedEntityTypes.remove(type);
        saveEntityData();
    }
}
