package com.portal.plugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PortalCommandHandler — handles all {@code /portal} subcommands.
 *
 * MAINT-03 fix: extracted from {@link Main} to satisfy the Single Responsibility Principle.
 * SEC-02 fix: {@link #resolveOfflinePlayer(Player, String)} warns the admin when
 * {@code Bukkit.getOfflinePlayer(name)} returns a player who has never joined the server
 * (i.e., {@code getName()} returns {@code null}), preventing silent UUID mismatches on
 * online-mode servers.
 */
public class PortalCommandHandler implements CommandExecutor {

    private final Main plugin;
    private final PortalRegistry portalRegistry;
    private final EconomyManager economyManager;
    private final PortalAccessManager accessManager;

    public PortalCommandHandler(Main plugin,
                                PortalRegistry portalRegistry,
                                EconomyManager economyManager,
                                PortalAccessManager accessManager) {
        this.plugin = plugin;
        this.portalRegistry = portalRegistry;
        this.economyManager = economyManager;
        this.accessManager = accessManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /portal create <name> <vertical|horizontal>");
                    return true;
                }
                handleCreateCommand(player, args[1], args[2]);
                break;

            case "link":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /portal link <portal1> <portal2>");
                    return true;
                }
                handleLinkCommand(player, args[1], args[2]);
                break;

            case "remove":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /portal remove <name>");
                    return true;
                }
                handleRemoveCommand(player, args[1]);
                break;

            case "confirm":
                economyManager.confirmCreation(player);
                break;

            case "finish":
                portalRegistry.finishPortalCreation(player);
                break;

            case "cancel":
                portalRegistry.cancelPortalCreation(player);
                economyManager.cancelPendingCreation(player.getUniqueId());
                break;

            case "list":
                handleListCommand(player);
                break;

            case "setcost":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /portal setcost <portal> <cost>");
                    return true;
                }
                handleSetCostCommand(player, args[1], args[2]);
                break;

            case "setplayercost":
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal setplayercost <player> <portal> <cost>");
                    return true;
                }
                handleSetPlayerCostCommand(player, args[1], args[2], args[3]);
                break;

            case "cost":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /portal cost <portal>");
                    return true;
                }
                handleCostCommand(player, args[1]);
                break;

            case "access":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /portal access <portal> <mode|allow|deny|remove|list|owner> [args]");
                    return true;
                }
                handleAccessCommand(player, args);
                break;

            default:
                player.sendMessage("§cUnknown subcommand. Use /portal for help.");
                break;
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== Portal Plugin Commands ===");
        player.sendMessage("§e/portal create <name> <vertical|horizontal> - Create a new portal");
        player.sendMessage("§e/portal confirm - Confirm portal creation and pay");
        player.sendMessage("§e/portal finish - Finish creating the current portal");
        player.sendMessage("§e/portal cancel - Cancel portal creation");
        player.sendMessage("§e/portal link <portal1> <portal2> - Link two portals");
        player.sendMessage("§e/portal remove <name> - Remove a portal");
        player.sendMessage("§e/portal list - List all portals");
        player.sendMessage("§e/portal setcost <portal> <cost> - Set portal cost (admin)");
        player.sendMessage("§e/portal setplayercost <player> <portal> <cost> - Set player cost (admin)");
        player.sendMessage("§e/portal cost <portal> - Check teleport cost");
        player.sendMessage("§6=== Access Control Commands ===");
        player.sendMessage("§e/portal access <portal> mode <public|private|whitelist|blacklist> - Set access mode");
        player.sendMessage("§e/portal access <portal> allow <player> - Allow player to use portal");
        player.sendMessage("§e/portal access <portal> deny <player> - Deny player from using portal");
        player.sendMessage("§e/portal access <portal> remove <player> - Remove player access");
        player.sendMessage("§e/portal access <portal> list - List portal permissions");
        player.sendMessage("§e/portal access <portal> owner <player> - Transfer ownership (owner/admin)");
    }

    private void handleCreateCommand(Player player, String name, String orientationStr) {
        if (!player.hasPermission("leverportal.create")) {
            player.sendMessage("§cYou don't have permission to create portals.");
            return;
        }

        if (!isValidPortalName(name)) {
            player.sendMessage("§cInvalid portal name. Use 3-32 alphanumeric characters, underscores, or hyphens.");
            return;
        }

        Portal.Orientation orientation;
        try {
            orientation = Portal.Orientation.valueOf(orientationStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage("§cInvalid orientation. Use 'vertical' or 'horizontal'.");
            return;
        }

        if (economyManager.isEconomyEnabled()) {
            economyManager.startCreationWithCost(player, name, orientation, 0);
        } else {
            portalRegistry.startPortalCreation(player, name, orientation);
            player.sendMessage("§aPortal creation started! Right-click blocks to add them to the portal.");
            player.sendMessage("§aUse /portal finish to complete the portal.");
        }
    }

    private void handleLinkCommand(Player player, String portal1Name, String portal2Name) {
        if (!player.hasPermission("leverportal.link") && !player.hasPermission("leverportal.admin")) {
            player.sendMessage("§cYou don't have permission to link portals.");
            return;
        }

        Portal portal1 = portalRegistry.getPortal(portal1Name);
        Portal portal2 = portalRegistry.getPortal(portal2Name);

        if (portal1 == null) {
            player.sendMessage("§cPortal '" + portal1Name + "' not found.");
            return;
        }

        if (portal2 == null) {
            player.sendMessage("§cPortal '" + portal2Name + "' not found.");
            return;
        }

        boolean isAdmin = player.hasPermission("leverportal.admin");
        boolean ownsPortal1 = accessManager.isOwner(portal1, player.getUniqueId());
        boolean ownsPortal2 = accessManager.isOwner(portal2, player.getUniqueId());

        if (!isAdmin && (!ownsPortal1 || !ownsPortal2)) {
            player.sendMessage("§cYou must own both portals to link them!");
            return;
        }

        if (portalRegistry.connectPortals(portal1, portal2)) {
            player.sendMessage("§aPortals linked successfully!");
        } else {
            player.sendMessage("§cCannot link these portals. They must have the same orientation and be at a reasonable distance.");
        }
    }

    private void handleRemoveCommand(Player player, String name) {
        Portal portal = portalRegistry.getPortal(name);
        if (portal == null) {
            player.sendMessage("§cPortal '" + name + "' not found.");
            return;
        }

        boolean isAdmin = player.hasPermission("leverportal.admin");
        boolean isOwner = accessManager.isOwner(portal, player.getUniqueId());

        if (!isAdmin && !isOwner) {
            player.sendMessage("§cYou can only remove portals you own.");
            return;
        }

        portalRegistry.removePortal(name);
        player.sendMessage("§aPortal '" + name + "' removed.");
    }

    private void handleListCommand(Player player) {
        List<String> portalNames = portalRegistry.getAllPortalNames();

        if (portalNames.isEmpty()) {
            player.sendMessage("§eNo portals created yet.");
            return;
        }

        player.sendMessage("§6=== Active Portals ===");
        for (String portalName : portalNames) {
            Portal portal = portalRegistry.getPortal(portalName);
            if (portal == null) continue;
            String status = portal.isActive() ? "§a[ACTIVE]" : "§c[INACTIVE]";
            String connectedTo = portalRegistry.findConnectedPortalId(portal);
            String connection = connectedTo != null ? " §7-> §e" + connectedTo : "";
            player.sendMessage("§e" + portalName + " " + status + connection);
        }
    }

    private void handleSetCostCommand(Player player, String portalName, String costStr) {
        if (!player.hasPermission("leverportal.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return;
        }

        try {
            int cost = Integer.parseInt(costStr);
            if (cost < 0) {
                player.sendMessage("§cCost must be zero or positive.");
                return;
            }
            economyManager.setPortalCost(portalName, cost);
            player.sendMessage("§aSet cost for portal '" + portalName + "' to " + cost + " diamonds.");
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid cost value. Must be a number.");
        }
    }

    private void handleSetPlayerCostCommand(Player player, String playerName, String portalName, String costStr) {
        if (!player.hasPermission("leverportal.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return;
        }

        Player targetPlayer = Bukkit.getPlayer(playerName);
        if (targetPlayer == null) {
            player.sendMessage("§cPlayer not found (must be online).");
            return;
        }

        try {
            int cost = Integer.parseInt(costStr);
            if (cost < 0) {
                player.sendMessage("§cCost must be zero or positive.");
                return;
            }
            economyManager.setPlayerPortalCost(targetPlayer.getUniqueId(), portalName, cost);
            player.sendMessage("§aSet cost for player '" + playerName + "' on portal '" + portalName + "' to " + cost + " diamonds.");
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid cost value. Must be a number.");
        }
    }

    private void handleCostCommand(Player player, String portalName) {
        int cost = economyManager.getTeleportCost(player, portalName);
        player.sendMessage("§eTeleportation cost for portal '" + portalName + "': " + cost + " diamonds");
    }

    private void handleAccessCommand(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /portal access <portal> <mode|allow|deny|remove|list|owner> [args]");
            return;
        }

        String portalName = args[1];
        String action = args[2].toLowerCase();

        Portal portal = portalRegistry.getPortal(portalName);
        if (portal == null) {
            player.sendMessage("§cPortal '" + portalName + "' not found.");
            return;
        }

        UUID playerId = player.getUniqueId();
        boolean isOwner = accessManager.isOwner(portal, playerId);
        boolean isAdmin = player.hasPermission("leverportal.admin");

        switch (action) {
            case "mode":
                if (!isOwner && !isAdmin) {
                    player.sendMessage(plugin.getConfig().getString("messages.not_owner", "§cYou are not the owner of this portal!"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal access <portal> mode <public|private|whitelist|blacklist>");
                    return;
                }
                try {
                    Portal.AccessMode mode = Portal.AccessMode.valueOf(args[3].toUpperCase());
                    accessManager.setPortalAccessMode(portal, mode);
                    player.sendMessage(plugin.getConfig().getString("messages.access_mode_changed", "§aPortal access mode changed to %mode%.")
                            .replace("%mode%", mode.name()));
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid access mode. Use: public, private, whitelist, or blacklist");
                }
                break;

            case "allow": {
                if (!isOwner && !isAdmin) {
                    player.sendMessage(plugin.getConfig().getString("messages.not_owner", "§cYou are not the owner of this portal!"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal access <portal> allow <player>");
                    return;
                }
                // SEC-02 fix: warn if the player has never joined this server
                OfflinePlayer targetOffline = resolveOfflinePlayer(player, args[3]);
                if (targetOffline == null) return;
                UUID targetId = targetOffline.getUniqueId();
                String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
                accessManager.allowPlayer(portal, targetId);
                player.sendMessage(plugin.getConfig().getString("messages.player_allowed", "§aPlayer %player% can now use this portal.")
                        .replace("%player%", targetName));
                break;
            }

            case "deny": {
                if (!isOwner && !isAdmin) {
                    player.sendMessage(plugin.getConfig().getString("messages.not_owner", "§cYou are not the owner of this portal!"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal access <portal> deny <player>");
                    return;
                }
                OfflinePlayer targetOffline = resolveOfflinePlayer(player, args[3]);
                if (targetOffline == null) return;
                UUID targetId = targetOffline.getUniqueId();
                String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
                accessManager.denyPlayer(portal, targetId);
                player.sendMessage(plugin.getConfig().getString("messages.player_denied", "§cPlayer %player% is now denied from using this portal.")
                        .replace("%player%", targetName));
                break;
            }

            case "remove": {
                if (!isOwner && !isAdmin) {
                    player.sendMessage(plugin.getConfig().getString("messages.not_owner", "§cYou are not the owner of this portal!"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal access <portal> remove <player>");
                    return;
                }
                OfflinePlayer targetOffline = resolveOfflinePlayer(player, args[3]);
                if (targetOffline == null) return;
                UUID targetId = targetOffline.getUniqueId();
                String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
                accessManager.removePlayerAccess(portal, targetId);
                player.sendMessage(plugin.getConfig().getString("messages.player_access_removed", "§ePlayer %player% access has been reset.")
                        .replace("%player%", targetName));
                break;
            }

            case "list": {
                player.sendMessage("§6=== Portal Access: " + portalName + " ===");
                UUID ownerId = accessManager.getOwnerId(portal);
                if (ownerId != null) {
                    OfflinePlayer ownerPlayer = Bukkit.getOfflinePlayer(ownerId);
                    String ownerName = ownerPlayer.getName() != null ? ownerPlayer.getName() : ownerId.toString();
                    player.sendMessage("§eOwner: " + ownerName);
                }
                player.sendMessage("§eAccess Mode: " + accessManager.getAccessMode(portal).name());

                Set<UUID> allowed = accessManager.getAllowedPlayers(portal);
                if (!allowed.isEmpty()) {
                    player.sendMessage("§aAllowed Players:");
                    for (UUID uuid : allowed) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        String name = op.getName() != null ? op.getName() : uuid.toString();
                        player.sendMessage("  §7- " + name);
                    }
                }

                Set<UUID> denied = accessManager.getDeniedPlayers(portal);
                if (!denied.isEmpty()) {
                    player.sendMessage("§cDenied Players:");
                    for (UUID uuid : denied) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        String name = op.getName() != null ? op.getName() : uuid.toString();
                        player.sendMessage("  §7- " + name);
                    }
                }
                break;
            }

            case "owner": {
                if (!isOwner && !isAdmin) {
                    player.sendMessage(plugin.getConfig().getString("messages.not_owner", "§cYou are not the owner of this portal!"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage("§cUsage: /portal access <portal> owner <player>");
                    return;
                }
                OfflinePlayer targetOffline = resolveOfflinePlayer(player, args[3]);
                if (targetOffline == null) return;
                UUID targetId = targetOffline.getUniqueId();
                String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
                accessManager.transferOwnership(portal, targetId);
                player.sendMessage("§aOwnership of portal '" + portalName + "' transferred to " + targetName);
                break;
            }

            default:
                player.sendMessage("§cUnknown action. Use: mode, allow, deny, remove, list, or owner");
                break;
        }
    }

    /**
     * SEC-02 fix: resolve an offline player by name, warning the admin if the player
     * has never joined this server (which would result in a wrong UUID on online-mode servers).
     *
     * @param sender   the command sender to warn
     * @param name     the player name to look up
     * @return the {@link OfflinePlayer}, or {@code null} if the lookup should be aborted
     */
    private OfflinePlayer resolveOfflinePlayer(Player sender, String name) {
        // First try online players for an exact match
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        // Fall back to offline player lookup
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);

        // SEC-02 fix: getName() returns null if the player has never joined this server.
        // On online-mode servers, the UUID generated by getOfflinePlayer(String) for unknown
        // players is an offline-mode UUID that will never match the real Mojang UUID.
        if (offline.getName() == null) {
            sender.sendMessage("§c⚠ Warning: Player '" + name + "' has never joined this server.");
            sender.sendMessage("§cOn online-mode servers, the UUID may be incorrect. The operation was aborted.");
            sender.sendMessage("§eThe target player must join the server at least once before you can manage their access.");
            return null;
        }

        return offline;
    }

    /**
     * Validate portal name format.
     */
    private boolean isValidPortalName(String name) {
        return name != null &&
               name.length() >= 3 &&
               name.length() <= 32 &&
               name.matches("[a-zA-Z0-9_-]+");
    }
}
