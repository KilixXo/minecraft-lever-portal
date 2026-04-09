package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LeverHandler - Manages lever interactions for portal activation.
 */
public class LeverHandler implements Listener {

    private final Main plugin;
    private final PortalRegistry registry;

    // H-3 fix: use String key instead of Location to avoid mutable-hash / floating-point issues
    // FIX-4: use ConcurrentHashMap for thread safety
    private final Map<String, String> leverToPortal = new ConcurrentHashMap<>();

    public LeverHandler(Main plugin, PortalRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * Serialise a Location to a stable block-coordinate key.
     */
    private static String locKey(Location loc) {
        World w = loc.getWorld();
        String worldName = (w != null) ? w.getName() : "unknown";
        return worldName + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /**
     * Handle lever click event.
     */
    @EventHandler
    public void onLeverClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.LEVER) {
            return;
        }

        Player player = event.getPlayer();
        Location leverLoc = clickedBlock.getLocation();
        String key = locKey(leverLoc);

        // Check if this lever is associated with a portal
        String portalId = leverToPortal.get(key);

        if (portalId != null) {
            Portal portal = registry.getPortal(portalId);
            if (portal != null) {
                togglePortal(player, portal, clickedBlock);
            }
        } else {
            checkAndAssociateLever(player, leverLoc);
        }
    }

    /**
     * Toggle a portal's active state.
     */
    private void togglePortal(Player player, Portal portal, Block leverBlock) {
        if (!(leverBlock.getBlockData() instanceof Switch)) {
            return;
        }
        Switch leverData = (Switch) leverBlock.getBlockData();
        boolean leverOn = leverData.isPowered();

        portal.setActive(leverOn);

        // Also toggle connected portal
        String connectedId = registry.findConnectedPortalId(portal);
        if (connectedId != null) {
            Portal connectedPortal = registry.getPortal(connectedId);
            if (connectedPortal != null) {
                connectedPortal.setActive(leverOn);
            }
        }

        if (leverOn) {
            player.sendMessage("§aPortal '" + portal.getId() + "' activated!");
        } else {
            player.sendMessage("§ePortal '" + portal.getId() + "' deactivated.");
        }
    }

    /**
     * Check if a lever can be associated with a nearby portal.
     *
     * PERF-01 fix: uses the chunk-based spatial index ({@link PortalRegistry#findPortalsNear})
     * instead of iterating all portals — O(1) chunk lookup vs O(n×m) full scan.
     *
     * BUG-05 fix: checks that the player owns the portal (or has admin permission)
     * before associating the lever, preventing unauthorized toggle control.
     */
    private void checkAndAssociateLever(Player player, Location leverLoc) {
        double maxDist = plugin.getConfig().getDouble("portal.max_lever_distance", 10.0);

        // PERF-01: use spatial index for O(1) chunk lookup
        List<Portal> nearby = registry.findPortalsNear(leverLoc, maxDist);

        for (Portal portal : nearby) {
            // BUG-05 fix: ownership / admin check before associating
            boolean isAdmin = player.hasPermission("leverportal.admin");
            boolean isOwner = portal.isOwner(player.getUniqueId());

            if (!isAdmin && !isOwner) {
                player.sendMessage("§cYou don't own portal '" + portal.getId() + "' and cannot associate a lever with it.");
                return;
            }

            // Associate this lever with the portal
            leverToPortal.put(locKey(leverLoc), portal.getId());
            portal.setLeverLocation(leverLoc);

            player.sendMessage("§aLever associated with portal '" + portal.getId() + "'!");
            player.sendMessage("§eToggle the lever to activate/deactivate the portal.");
            return;
        }

        player.sendMessage("§cNo portal found nearby. Create a portal first, then place a lever within " + (int) maxDist + " blocks.");
    }

    /**
     * Associate a lever with a specific portal (called during load to restore associations).
     */
    public void associateLever(Location leverLoc, String portalId) {
        leverToPortal.put(locKey(leverLoc), portalId);
        Portal portal = registry.getPortal(portalId);
        if (portal != null) {
            portal.setLeverLocation(leverLoc);
        }
    }

    /**
     * Remove lever association.
     */
    public void removeLeverAssociation(Location leverLoc) {
        String portalId = leverToPortal.remove(locKey(leverLoc));
        if (portalId != null) {
            Portal portal = registry.getPortal(portalId);
            if (portal != null) {
                portal.setLeverLocation(null);
            }
        }
    }

    /**
     * Get the portal ID associated with a lever location.
     */
    public String getAssociatedPortal(Location leverLoc) {
        return leverToPortal.get(locKey(leverLoc));
    }
}
