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

import java.util.HashMap;
import java.util.Map;

/**
 * LeverHandler - Manages lever interactions for portal activation.
 */
public class LeverHandler implements Listener {

    // M-9: max lever-to-portal association distance (configurable constant)
    public static final double MAX_LEVER_DISTANCE = 10.0;

    private final Main plugin;
    private final PortalRegistry registry;

    // H-3 fix: use String key instead of Location to avoid mutable-hash / floating-point issues
    private final Map<String, String> leverToPortal = new HashMap<>();

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
     */
    private void checkAndAssociateLever(Player player, Location leverLoc) {
        for (String portalName : registry.getAllPortalNames()) {
            Portal portal = registry.getPortal(portalName);
            if (portal == null) continue;

            Location portalCenter = portal.getCenter();
            if (portalCenter == null) continue;

            World portalWorld = portalCenter.getWorld();
            World leverWorld = leverLoc.getWorld();

            // H-6 guard: ensure same world before calling distance()
            if (portalWorld != null && portalWorld.equals(leverWorld)
                && portalCenter.distance(leverLoc) <= MAX_LEVER_DISTANCE) {

                // Associate this lever with the portal (H-3: string key)
                leverToPortal.put(locKey(leverLoc), portal.getId());
                portal.setLeverLocation(leverLoc);

                player.sendMessage("§aLever associated with portal '" + portal.getId() + "'!");
                player.sendMessage("§eToggle the lever to activate/deactivate the portal.");
                return;
            }
        }

        player.sendMessage("§cNo portal found nearby. Create a portal first, then place a lever within " + (int) MAX_LEVER_DISTANCE + " blocks.");
    }

    /**
     * Associate a lever with a specific portal (H-7: called during load to restore associations).
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
