package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * PortalEventListener — handles Bukkit events related to portal usage.
 *
 * MAINT-03 fix: extracted from {@link Main} to satisfy the Single Responsibility Principle.
 * {@link Main} now only handles plugin lifecycle; event handling lives here.
 */
public class PortalEventListener implements Listener {

    private final Main plugin;
    private final PortalRegistry portalRegistry;
    private final EconomyManager economyManager;

    public PortalEventListener(Main plugin, PortalRegistry portalRegistry, EconomyManager economyManager) {
        this.plugin = plugin;
        this.portalRegistry = portalRegistry;
        this.economyManager = economyManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || event.getFrom().getBlock().equals(to.getBlock())) {
            return; // Player hasn't moved to a new block
        }
        portalRegistry.handlePlayerEnteringPortal(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up pending creations and sessions
        economyManager.cancelPendingCreation(event.getPlayer().getUniqueId());
        portalRegistry.cancelPortalCreation(event.getPlayer());
    }
}
