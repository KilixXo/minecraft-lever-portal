package com.portal.plugin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EconomyManager - Manages diamond-based economy for portal usage and creation.
 */
public class EconomyManager {
    
    private final Main plugin;
    private boolean economyEnabled;
    private int globalTeleportCost;
    private int baseCost;
    private int blocksPerDiamond;
    private int diamondCostPerDistance;
    private int maxCost;
    
    // Portal-specific costs
    private final Map<String, Integer> portalCosts = new HashMap<>();
    
    // Player-specific costs per portal
    private final Map<UUID, Map<String, Integer>> playerCosts = new HashMap<>();
    
    // Pending portal creations awaiting confirmation
    private final Map<UUID, PendingCreation> pendingCreations = new HashMap<>();
    
    public EconomyManager(Main plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * Load configuration from config.yml
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        
        economyEnabled = plugin.getConfig().getBoolean("economy.enabled", true);
        globalTeleportCost = plugin.getConfig().getInt("economy.global_teleport_cost", 1);
        baseCost = plugin.getConfig().getInt("economy.creation.base_cost", 10);
        blocksPerDiamond = plugin.getConfig().getInt("economy.creation.blocks_per_diamond", 100);
        diamondCostPerDistance = plugin.getConfig().getInt("economy.creation.diamond_cost_per_distance", 5);
        maxCost = plugin.getConfig().getInt("economy.creation.max_cost", 1000);
        
        // Load portal-specific costs
        portalCosts.clear();
        ConfigurationSection portalSection = plugin.getConfig().getConfigurationSection("portal_costs");
        if (portalSection != null) {
            for (String portalName : portalSection.getKeys(false)) {
                portalCosts.put(portalName, portalSection.getInt(portalName));
            }
        }
        
        // Load player-specific costs
        playerCosts.clear();
        ConfigurationSection playerSection = plugin.getConfig().getConfigurationSection("player_costs");
        if (playerSection != null) {
            for (String uuidStr : playerSection.getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                Map<String, Integer> costs = new HashMap<>();
                ConfigurationSection playerPortals = playerSection.getConfigurationSection(uuidStr);
                if (playerPortals != null) {
                    for (String portalName : playerPortals.getKeys(false)) {
                        costs.put(portalName, playerPortals.getInt(portalName));
                    }
                }
                playerCosts.put(uuid, costs);
            }
        }
    }
    
    /**
     * Save configuration to config.yml
     */
    public void saveConfig() {
        plugin.getConfig().set("portal_costs", null);
        for (Map.Entry<String, Integer> entry : portalCosts.entrySet()) {
            plugin.getConfig().set("portal_costs." + entry.getKey(), entry.getValue());
        }
        
        plugin.getConfig().set("player_costs", null);
        for (Map.Entry<UUID, Map<String, Integer>> playerEntry : playerCosts.entrySet()) {
            for (Map.Entry<String, Integer> portalEntry : playerEntry.getValue().entrySet()) {
                plugin.getConfig().set("player_costs." + playerEntry.getKey() + "." + portalEntry.getKey(), 
                                      portalEntry.getValue());
            }
        }
        
        plugin.saveConfig();
    }
    
    /**
     * Calculate portal creation cost based on distance.
     */
    public int calculateCreationCost(double distance) {
        if (!economyEnabled) return 0;
        
        int distanceCost = (int) ((distance / blocksPerDiamond) * diamondCostPerDistance);
        int totalCost = baseCost + distanceCost;
        
        return Math.min(totalCost, maxCost);
    }
    
    /**
     * Get teleportation cost for a player through a specific portal.
     */
    public int getTeleportCost(Player player, String portalName) {
        if (!economyEnabled) return 0;
        
        // Check player-specific cost first
        Map<String, Integer> playerPortalCosts = playerCosts.get(player.getUniqueId());
        if (playerPortalCosts != null && playerPortalCosts.containsKey(portalName)) {
            return playerPortalCosts.get(portalName);
        }
        
        // Check portal-specific cost
        if (portalCosts.containsKey(portalName)) {
            return portalCosts.get(portalName);
        }
        
        // Return global cost
        return globalTeleportCost;
    }
    
    /**
     * Set portal-specific cost for all players.
     */
    public void setPortalCost(String portalName, int cost) {
        if (cost <= 0) {
            portalCosts.remove(portalName);
        } else {
            portalCosts.put(portalName, cost);
        }
        saveConfig();
    }
    
    /**
     * Set player-specific cost for a portal.
     */
    public void setPlayerPortalCost(UUID playerId, String portalName, int cost) {
        Map<String, Integer> costs = playerCosts.computeIfAbsent(playerId, k -> new HashMap<>());
        if (cost <= 0) {
            costs.remove(portalName);
            if (costs.isEmpty()) {
                playerCosts.remove(playerId);
            }
        } else {
            costs.put(portalName, cost);
        }
        saveConfig();
    }
    
    /**
     * Count diamonds in player's inventory.
     */
    public int countDiamonds(Player player) {
        PlayerInventory inv = player.getInventory();
        int count = 0;
        
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == Material.DIAMOND) {
                count += item.getAmount();
            }
        }
        
        return count;
    }
    
    /**
     * Remove diamonds from player's inventory.
     */
    public boolean removeDiamonds(Player player, int amount) {
        if (amount <= 0) return true;
        
        PlayerInventory inv = player.getInventory();
        int remaining = amount;
        
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.DIAMOND) {
                int stackAmount = item.getAmount();
                if (stackAmount <= remaining) {
                    inv.setItem(i, null);
                    remaining -= stackAmount;
                } else {
                    item.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
                
                if (remaining == 0) break;
            }
        }
        
        return remaining == 0;
    }
    
    /**
     * Charge player for teleportation.
     */
    public boolean chargeTeleport(Player player, String portalName) {
        if (!economyEnabled) return true;
        
        int cost = getTeleportCost(player, portalName);
        if (cost <= 0) return true;
        
        int balance = countDiamonds(player);
        if (balance < cost) {
            String msg = plugin.getConfig().getString("messages.insufficient_funds", 
                "§cYou don't have enough diamonds! Required: %cost%, You have: %balance%");
            msg = msg.replace("%cost%", String.valueOf(cost))
                     .replace("%balance%", String.valueOf(balance));
            player.sendMessage(msg);
            return false;
        }
        
        if (removeDiamonds(player, cost)) {
            String msg = plugin.getConfig().getString("messages.payment_success", 
                "§aPaid %cost% diamonds for teleportation.");
            msg = msg.replace("%cost%", String.valueOf(cost));
            player.sendMessage(msg);
            return true;
        }
        
        return false;
    }
    
    /**
     * Start portal creation with cost calculation.
     */
    public void startCreationWithCost(Player player, String portalName, Portal.Orientation orientation, 
                                      double distance) {
        if (!economyEnabled) {
            // No economy, proceed directly
            plugin.getPortalRegistry().startPortalCreation(player, portalName, orientation);
            return;
        }
        
        int cost = calculateCreationCost(distance);
        
        PendingCreation pending = new PendingCreation(portalName, orientation, cost);
        pendingCreations.put(player.getUniqueId(), pending);
        
        String msg = plugin.getConfig().getString("messages.creation_cost", 
            "§ePortal creation will cost %cost% diamonds. Type /portal confirm to proceed.");
        msg = msg.replace("%cost%", String.valueOf(cost));
        player.sendMessage(msg);
    }
    
    /**
     * Confirm portal creation and charge player.
     */
    public boolean confirmCreation(Player player) {
        PendingCreation pending = pendingCreations.get(player.getUniqueId());
        if (pending == null) {
            player.sendMessage("§cNo pending portal creation.");
            return false;
        }
        
        int cost = pending.cost;
        int balance = countDiamonds(player);
        
        if (balance < cost) {
            String msg = plugin.getConfig().getString("messages.insufficient_funds", 
                "§cYou don't have enough diamonds! Required: %cost%, You have: %balance%");
            msg = msg.replace("%cost%", String.valueOf(cost))
                     .replace("%balance%", String.valueOf(balance));
            player.sendMessage(msg);
            return false;
        }
        
        if (removeDiamonds(player, cost)) {
            plugin.getPortalRegistry().startPortalCreation(player, pending.portalName, pending.orientation);
            pendingCreations.remove(player.getUniqueId());
            
            String msg = plugin.getConfig().getString("messages.creation_success", 
                "§aPortal created! Paid %cost% diamonds.");
            msg = msg.replace("%cost%", String.valueOf(cost));
            player.sendMessage(msg);
            return true;
        }
        
        return false;
    }
    
    /**
     * Cancel pending creation.
     */
    public void cancelPendingCreation(UUID playerId) {
        pendingCreations.remove(playerId);
    }
    
    /**
     * Check if player has pending creation.
     */
    public boolean hasPendingCreation(UUID playerId) {
        return pendingCreations.containsKey(playerId);
    }
    
    public boolean isEconomyEnabled() {
        return economyEnabled;
    }
    
    /**
     * Helper class for pending portal creations.
     */
    private static class PendingCreation {
        final String portalName;
        final Portal.Orientation orientation;
        final int cost;
        
        PendingCreation(String portalName, Portal.Orientation orientation, int cost) {
            this.portalName = portalName;
            this.orientation = orientation;
            this.cost = cost;
        }
    }
}
