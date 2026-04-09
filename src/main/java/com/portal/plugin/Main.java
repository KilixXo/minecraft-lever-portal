package com.portal.plugin;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Portal Plugin — Dual Connected Portals with Lever Activation.
 *
 * MAINT-03 fix: {@link Main} now only handles plugin lifecycle (enable/disable).
 * Event handling is delegated to {@link PortalEventListener} and
 * {@link LeverHandler}; command handling is delegated to {@link PortalCommandHandler}.
 *
 * Features:
 * - Create two connected portals using arbitrary blocks in vertical or horizontal orientation
 * - Activate/deactivate portals using levers
 * - Pass through portals in both directions when active
 */
public class Main extends JavaPlugin {

    private PortalRegistry portalRegistry;
    private LeverHandler leverHandler;
    private EconomyManager economyManager;
    private PortalAccessManager accessManager;

    @Override
    public void onEnable() {
        // Save default config (must happen before any component reads config)
        saveDefaultConfig();

        // FIX-9: apply configurable player detection radius before any Portal is created
        double detectionRadius = getConfig().getDouble("portal.player_detection_radius", 1.5);
        Portal.setPlayerDetectionRadius(detectionRadius);

        // Initialize core systems
        this.economyManager = new EconomyManager(this);
        this.accessManager  = new PortalAccessManager(this);
        this.portalRegistry = new PortalRegistry(this, economyManager);
        this.portalRegistry.setAccessManager(this.accessManager);
        this.portalRegistry.initStorage();
        this.leverHandler   = new LeverHandler(this, this.portalRegistry);

        // Register event listeners (MAINT-03: extracted into dedicated classes)
        PortalEventListener eventListener = new PortalEventListener(this, portalRegistry, economyManager);
        getServer().getPluginManager().registerEvents(eventListener, this);
        getServer().getPluginManager().registerEvents(leverHandler, this);

        // Also register the interaction handler (portal block right-click during creation)
        getServer().getPluginManager().registerEvents(new PortalInteractListener(portalRegistry), this);

        // Register command handler (MAINT-03: extracted into dedicated class)
        // RT-01 fix: guard against getCommand() returning null (e.g., if plugin.yml is missing
        // the command declaration or the plugin is loaded in an unexpected way).
        PortalCommandHandler commandHandler = new PortalCommandHandler(
            this, portalRegistry, economyManager, accessManager);
        org.bukkit.command.PluginCommand portalCmd = getCommand("portal");
        if (portalCmd != null) {
            portalCmd.setExecutor(commandHandler);
        } else {
            getLogger().severe("Could not register /portal command — is 'portal' declared in plugin.yml?");
        }

        // Load saved portals
        portalRegistry.loadAllPortals();

        // Restore lever associations from loaded portal data
        Map<String, Location> leverAssociations = portalRegistry.getLeverAssociations();
        for (Map.Entry<String, Location> entry : leverAssociations.entrySet()) {
            leverHandler.associateLever(entry.getValue(), entry.getKey());
        }

        getLogger().info("Portal Plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Save all active portals before shutdown
        if (this.portalRegistry != null) {
            this.portalRegistry.saveAllPortals();
            this.portalRegistry.closeStorage();
        }
        getLogger().info("Portal Plugin disabled.");
    }

    // ── Accessors for components that need a reference back to the plugin ──

    public PortalRegistry getPortalRegistry() {
        return portalRegistry;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public PortalAccessManager getAccessManager() {
        return accessManager;
    }
}
