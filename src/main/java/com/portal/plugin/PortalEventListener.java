package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

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
        UUID playerId = event.getPlayer().getUniqueId();
        // FIX-A4: refund diamonds if the player paid for a portal but quit before /portal finish
        economyManager.refundIfPaid(event.getPlayer());
        // Clean up pending pre-payment creations and sessions
        economyManager.cancelPendingCreation(playerId);
        portalRegistry.cancelPortalCreation(event.getPlayer());
        // FIX-3: also clear teleport cooldown state so the player is not
        // permanently locked out if they quit while a cooldown was active.
        portalRegistry.clearTeleportState(playerId);
    }
}
