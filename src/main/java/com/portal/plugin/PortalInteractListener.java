package com.portal.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * PortalInteractListener — handles {@link PlayerInteractEvent} for portal block selection
 * during portal creation sessions.
 *
 * MAINT-03 fix: extracted from {@link Main} as part of the SRP refactor.
 * Note: {@link LeverHandler} also listens to {@link PlayerInteractEvent} for lever clicks;
 * both listeners are registered independently and Bukkit dispatches to both.
 */
public class PortalInteractListener implements Listener {

    private final PortalRegistry portalRegistry;

    public PortalInteractListener(PortalRegistry portalRegistry) {
        this.portalRegistry = portalRegistry;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        portalRegistry.handlePortalInteraction(event);
    }
}
