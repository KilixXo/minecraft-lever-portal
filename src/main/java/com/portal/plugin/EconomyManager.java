package com.portal.plugin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EconomyManager - Manages diamond-based economy for portal usage and creation.
 *
 * <p>Runtime portal/player costs are stored in a separate {@code data.yml} file
 * (SEC-04 fix) rather than in {@code config.yml}, so that static configuration
 * and dynamic runtime data are never mixed.
 */
public class EconomyManager {

    private final Main plugin;
    private boolean economyEnabled;
    private int globalTeleportCost;
    private int baseCost;
    private int blocksPerDiamond;
    private int diamondCostPerDistance;
    private int maxCost;

    // Portal-specific costs (runtime data — stored in data.yml)
    // FIX-17: use ConcurrentHashMap for thread safety (saveData() may be called from async context)
    private final Map<String, Integer> portalCosts = new ConcurrentHashMap<>();

    // Player-specific costs per portal (runtime data — stored in data.yml)
    private final Map<UUID, Map<String, Integer>> playerCosts = new ConcurrentHashMap<>();

    // Pending portal creations awaiting confirmation
    private final Map<UUID, PendingCreation> pendingCreations = new ConcurrentHashMap<>();

    // FIX-A4: track the cost paid for an in-progress creation session so we can
    // refund diamonds if the player disconnects before /portal finish.
    private final Map<UUID, Integer> paidCreationCosts = new ConcurrentHashMap<>();

    // SEC-04: separate file for runtime cost data
    private File dataFile;
    private FileConfiguration dataConfig;

    public EconomyManager(Main plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        loadConfig();
        loadData();
    }

    /**
     * Load static configuration from config.yml.
     * BUG-01 fix: removed saveDefaultConfig() and reloadConfig() calls —
     * the config is already saved/loaded by Main.onEnable() before this constructor runs.
     */
    public void loadConfig() {
        // Read directly from the already-loaded config — do NOT call reloadConfig()
        FileConfiguration cfg = plugin.getConfig();

        economyEnabled        = cfg.getBoolean("economy.enabled", true);
        globalTeleportCost    = cfg.getInt("economy.global_teleport_cost", 1);
        baseCost              = cfg.getInt("economy.creation.base_cost", 10);
        blocksPerDiamond      = cfg.getInt("economy.creation.blocks_per_diamond", 100);
        diamondCostPerDistance = cfg.getInt("economy.creation.diamond_cost_per_distance", 5);
        maxCost               = cfg.getInt("economy.creation.max_cost", 1000);
    }

    /**
     * Load runtime portal/player cost data from data.yml (SEC-04 fix).
     */
    public void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        // Load portal-specific costs
        portalCosts.clear();
        ConfigurationSection portalSection = dataConfig.getConfigurationSection("portal_costs");
        if (portalSection != null) {
            for (String portalName : portalSection.getKeys(false)) {
                portalCosts.put(portalName, portalSection.getInt(portalName));
            }
        }

        // Load player-specific costs
        playerCosts.clear();
        ConfigurationSection playerSection = dataConfig.getConfigurationSection("player_costs");
        if (playerSection != null) {
            for (String uuidStr : playerSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    Map<String, Integer> costs = new ConcurrentHashMap<>();
                    ConfigurationSection playerPortals = playerSection.getConfigurationSection(uuidStr);
                    if (playerPortals != null) {
                        for (String portalName : playerPortals.getKeys(false)) {
                            costs.put(portalName, playerPortals.getInt(portalName));
                        }
                    }
                    playerCosts.put(uuid, costs);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in data.yml: " + uuidStr);
                }
            }
        }
    }

    /**
     * Save runtime portal/player cost data to data.yml (SEC-04 fix).
     * Static config settings are never written here.
     */
    public void saveData() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }

        dataConfig.set("portal_costs", null);
        for (Map.Entry<String, Integer> entry : portalCosts.entrySet()) {
            dataConfig.set("portal_costs." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("player_costs", null);
        for (Map.Entry<UUID, Map<String, Integer>> playerEntry : playerCosts.entrySet()) {
            for (Map.Entry<String, Integer> portalEntry : playerEntry.getValue().entrySet()) {
                dataConfig.set("player_costs." + playerEntry.getKey() + "." + portalEntry.getKey(),
                               portalEntry.getValue());
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[Economy] Failed to save data.yml: " + e.getMessage());
        }
    }

    /**
     * Calculate portal creation cost based on distance.
     */
    public int calculateCreationCost(double distance) {
        if (!economyEnabled) return 0;
        if (distance < 0) distance = 0;

        // Use long to prevent integer overflow
        long distanceCost = (long) ((distance / blocksPerDiamond) * diamondCostPerDistance);
        long totalCost = baseCost + distanceCost;

        // Clamp to valid range
        return (int) Math.min(Math.max(totalCost, 0), maxCost);
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
     * BUG-07 fix: cost=0 is stored explicitly (free portal) rather than removing the entry.
     * Only negative values remove the override and fall back to global cost.
     */
    public void setPortalCost(String portalName, int cost) {
        if (cost < 0) {
            portalCosts.remove(portalName);
        } else {
            // cost=0 means "explicitly free"; cost>0 means custom cost
            portalCosts.put(portalName, cost);
        }
        saveData();
    }

    /**
     * Set player-specific cost for a portal.
     * BUG-07 fix: same semantics — 0 = explicitly free, negative = remove override.
     */
    public void setPlayerPortalCost(UUID playerId, String portalName, int cost) {
        Map<String, Integer> costs = playerCosts.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        if (cost < 0) {
            costs.remove(portalName);
            if (costs.isEmpty()) {
                playerCosts.remove(playerId);
            }
        } else {
            costs.put(portalName, cost);
        }
        saveData();
    }

    /**
     * PERF-05 fix: Try to remove {@code amount} diamonds from the player's inventory in a
     * single pass. Returns {@code true} if successful, {@code false} if the player does not
     * have enough diamonds (inventory is not modified in that case).
     */
    public boolean tryRemoveDiamonds(Player player, int amount) {
        if (amount <= 0) return true;

        PlayerInventory inv = player.getInventory();

        // First pass: count available diamonds
        int available = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == Material.DIAMOND) {
                available += item.getAmount();
                if (available >= amount) break;
            }
        }
        if (available < amount) return false;

        // Second pass: remove diamonds (only reached if we have enough)
        int remaining = amount;
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
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
            }
        }
        return true;
    }

    /**
     * Count diamonds in player's inventory.
     * Kept for external use (e.g., cost display); teleport charging uses tryRemoveDiamonds().
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
     * Charge player for teleportation.
     *
     * FIX-6: removed the redundant countDiamonds() pre-check. tryRemoveDiamonds()
     * already performs a single-pass check-and-remove internally, so the previous
     * code was doing 3 inventory passes (count + check + remove) instead of 2
     * (check + remove). We now call tryRemoveDiamonds() directly and only call
     * countDiamonds() when we need to show the current balance in the error message.
     */
    public boolean chargeTeleport(Player player, String portalName) {
        if (!economyEnabled) return true;

        int cost = getTeleportCost(player, portalName);
        if (cost <= 0) return true;

        if (tryRemoveDiamonds(player, cost)) {
            String msg = plugin.getConfig().getString("messages.payment_success",
                "§aPaid %cost% diamonds for teleportation.");
            msg = msg.replace("%cost%", String.valueOf(cost));
            player.sendMessage(msg);
            return true;
        }

        // Not enough diamonds — show informative message with current balance
        int balance = countDiamonds(player);
        String msg = plugin.getConfig().getString("messages.insufficient_funds",
            "§cYou don't have enough diamonds! Required: %cost%, You have: %balance%");
        msg = msg.replace("%cost%", String.valueOf(cost))
                 .replace("%balance%", String.valueOf(balance));
        player.sendMessage(msg);
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

        if (tryRemoveDiamonds(player, cost)) {
            plugin.getPortalRegistry().startPortalCreation(player, pending.portalName, pending.orientation);
            pendingCreations.remove(player.getUniqueId());

            // FIX-A4: record the paid cost so it can be refunded if the player quits
            // before completing /portal finish.
            if (cost > 0) {
                paidCreationCosts.put(player.getUniqueId(), cost);
            }

            String msg = plugin.getConfig().getString("messages.creation_success",
                "§aPortal created! Paid %cost% diamonds.");
            msg = msg.replace("%cost%", String.valueOf(cost));
            player.sendMessage(msg);
            return true;
        }

        return false;
    }

    /**
     * FIX-A4: called when the portal creation is successfully finished.
     * Clears the paid-cost entry so we don't refund on the next quit.
     */
    public void clearPaidCreation(UUID playerId) {
        paidCreationCosts.remove(playerId);
    }

    /**
     * FIX-A4: refund diamonds to a player if they have a paid-but-unfinished creation
     * session (e.g., they disconnected before /portal finish). The player must be online
     * for the refund to succeed; otherwise the cost is silently cleared.
     */
    public void refundIfPaid(Player player) {
        Integer paidCost = paidCreationCosts.remove(player.getUniqueId());
        if (paidCost != null && paidCost > 0 && player.isOnline()) {
            // Give diamonds back by adding them to the inventory
            org.bukkit.inventory.ItemStack refund =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, paidCost);
            java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover =
                player.getInventory().addItem(refund);
            if (!leftover.isEmpty()) {
                // Inventory full — drop at player's feet
                for (org.bukkit.inventory.ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            player.sendMessage("§ePortal creation cancelled. " + paidCost + " diamond(s) refunded.");
        }
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
