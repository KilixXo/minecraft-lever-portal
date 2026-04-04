package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
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
    
    private final Main plugin;
    private final PortalRegistry registry;
    private final Map<Location, String> leverToPortal = new HashMap<>();
    
    public LeverHandler(Main plugin, PortalRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }
    
    /**
     * Handle lever click event.
     */
    @EventHandler
    public void onLeverClick(PlayerInteractEvent event) {
        // Only handle physical lever interactions
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.LEVER) {
            return;
        }
        
        Player player = event.getPlayer();
        Location leverLoc = clickedBlock.getLocation();
        
        // Check if this lever is associated with a portal
        String portalId = leverToPortal.get(leverLoc);
        
        if (portalId != null) {
            // Toggle the portal
            Portal portal = registry.getPortal(portalId);
            if (portal != null) {
                togglePortal(player, portal, clickedBlock);
            }
        } else {
            // Check if player is near a portal to associate this lever
            checkAndAssociateLever(player, leverLoc);
        }
    }
    
    /**
     * Toggle a portal's active state.
     */
    private void togglePortal(Player player, Portal portal, Block leverBlock) {
        // Get lever state
        Switch leverData = (Switch) leverBlock.getBlockData();
        boolean leverOn = leverData.isPowered();
        
        // Set portal active state based on lever
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
        // Find portals near this lever
        for (String portalName : registry.getAllPortalNames()) {
            Portal portal = registry.getPortal(portalName);
            if (portal == null) continue;
            
            Location portalCenter = portal.getCenter();
            if (portalCenter == null) continue;
            
            // Check if lever is within 10 blocks of portal center
            if (portalCenter.getWorld().equals(leverLoc.getWorld()) &&
                portalCenter.distance(leverLoc) <= 10) {
                
                // Associate this lever with the portal
                leverToPortal.put(leverLoc, portal.getId());
                portal.setLeverLocation(leverLoc);
                
                player.sendMessage("§aLever associated with portal '" + portal.getId() + "'!");
                player.sendMessage("§eToggle the lever to activate/deactivate the portal.");
                return;
            }
        }
        
        player.sendMessage("§cNo portal found nearby. Create a portal first, then place a lever within 10 blocks.");
    }
    
    /**
     * Associate a lever with a specific portal.
     */
    public void associateLever(Location leverLoc, String portalId) {
        leverToPortal.put(leverLoc, portalId);
        Portal portal = registry.getPortal(portalId);
        if (portal != null) {
            portal.setLeverLocation(leverLoc);
        }
    }
    
    /**
     * Remove lever association.
     */
    public void removeLeverAssociation(Location leverLoc) {
        String portalId = leverToPortal.remove(leverLoc);
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
        return leverToPortal.get(leverLoc);
    }
}
