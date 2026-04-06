package com.portal.plugin.storage;

import com.google.gson.*;
import com.portal.plugin.Main;
import com.portal.plugin.Portal;
import com.portal.plugin.PortalConnection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JsonPortalStorage — persists portal data as a JSON file.
 *
 * File location: {@code plugins/LeverPortal/portals/portals.json}
 */
public class JsonPortalStorage implements PortalStorage {

    private final Main plugin;
    private final File dataFolder;

    public JsonPortalStorage(Main plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "portals");
    }

    @Override
    public void init() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        plugin.getLogger().info("[Storage] Using JSON file backend.");
    }

    @Override
    public void saveAll(List<Portal> portals, List<PortalConnection> connections) {
        File portalFile = new File(dataFolder, "portals.json");
        try (FileWriter writer = new FileWriter(portalFile)) {
            writer.write("{\n");
            writer.write("  \"portals\": [\n");

            for (int i = 0; i < portals.size(); i++) {
                writer.write("    " + portals.get(i).toJson());
                if (i < portals.size() - 1) writer.write(",");
                writer.write("\n");
            }

            writer.write("  ],\n");
            writer.write("  \"connections\": [\n");

            for (int i = 0; i < connections.size(); i++) {
                PortalConnection conn = connections.get(i);
                writer.write("    {\"portal1\":\"" + escapeJson(conn.getPortal1Id()) +
                           "\",\"portal2\":\"" + escapeJson(conn.getPortal2Id()) + "\"}");
                if (i < connections.size() - 1) writer.write(",");
                writer.write("\n");
            }

            writer.write("  ]\n");
            writer.write("}\n");

            plugin.getLogger().info("[Storage] Saved " + portals.size() + " portals and " +
                                   connections.size() + " connections to JSON.");
        } catch (IOException e) {
            plugin.getLogger().severe("[Storage] Failed to save portals.json: " + e.getMessage());
        }
    }

    @Override
    public List<Portal> loadPortals() {
        List<Portal> result = new ArrayList<>();
        File portalFile = new File(dataFolder, "portals.json");
        if (!portalFile.exists()) {
            return result;
        }

        try {
            String content = Files.readString(portalFile.toPath());
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            if (!root.has("portals")) return result;

            for (JsonElement el : root.getAsJsonArray("portals")) {
                Portal portal = parsePortal(el.getAsJsonObject());
                if (portal != null) {
                    result.add(portal);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("[Storage] Failed to read portals.json: " + e.getMessage());
        } catch (JsonSyntaxException | IllegalStateException e) {
            plugin.getLogger().severe("[Storage] Failed to parse portals.json: " + e.getMessage());
        }

        return result;
    }

    @Override
    public List<PortalConnection> loadConnections() {
        List<PortalConnection> result = new ArrayList<>();
        File portalFile = new File(dataFolder, "portals.json");
        if (!portalFile.exists()) {
            return result;
        }

        try {
            String content = Files.readString(portalFile.toPath());
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            if (!root.has("connections")) return result;

            for (JsonElement el : root.getAsJsonArray("connections")) {
                JsonObject obj = el.getAsJsonObject();
                String p1 = obj.get("portal1").getAsString();
                String p2 = obj.get("portal2").getAsString();
                result.add(new PortalConnection(p1, p2));
            }
        } catch (IOException e) {
            plugin.getLogger().severe("[Storage] Failed to read connections from portals.json: " + e.getMessage());
        } catch (JsonSyntaxException | IllegalStateException e) {
            plugin.getLogger().severe("[Storage] Failed to parse connections from portals.json: " + e.getMessage());
        }

        return result;
    }

    @Override
    public void close() {
        // Nothing to close for file-based storage
    }

    // ── Helpers ──

    private Portal parsePortal(JsonObject obj) {
        try {
            String id = obj.get("id").getAsString();
            Portal.Orientation orientation = Portal.Orientation.valueOf(obj.get("orientation").getAsString());

            List<Location> blocks = new ArrayList<>();
            if (obj.has("blocks")) {
                for (JsonElement blockEl : obj.getAsJsonArray("blocks")) {
                    JsonObject blockObj = blockEl.getAsJsonObject();
                    String worldName = blockObj.get("world").getAsString();
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        plugin.getLogger().warning("[Storage] World '" + worldName + "' not found, skipping block in portal '" + id + "'");
                        continue;
                    }
                    blocks.add(new Location(world,
                        blockObj.get("x").getAsInt(),
                        blockObj.get("y").getAsInt(),
                        blockObj.get("z").getAsInt()));
                }
            }

            if (blocks.isEmpty()) {
                plugin.getLogger().warning("[Storage] Portal '" + id + "' has no valid blocks, skipping.");
                return null;
            }

            Portal portal = new Portal(id, orientation, blocks);

            if (obj.has("active")) portal.setActive(obj.get("active").getAsBoolean());

            if (obj.has("owner")) {
                try { portal.setOwnerId(UUID.fromString(obj.get("owner").getAsString())); }
                catch (IllegalArgumentException e) { plugin.getLogger().warning("[Storage] Invalid owner UUID for portal '" + id + "'"); }
            }

            if (obj.has("accessMode")) {
                try { portal.setAccessMode(Portal.AccessMode.valueOf(obj.get("accessMode").getAsString())); }
                catch (IllegalArgumentException e) { plugin.getLogger().warning("[Storage] Invalid access mode for portal '" + id + "'"); }
            }

            if (obj.has("allowedPlayers")) {
                for (JsonElement apEl : obj.getAsJsonArray("allowedPlayers")) {
                    try { portal.allowPlayer(UUID.fromString(apEl.getAsString())); }
                    catch (IllegalArgumentException e) { plugin.getLogger().warning("[Storage] Invalid UUID in allowedPlayers for portal '" + id + "'"); }
                }
            }

            if (obj.has("deniedPlayers")) {
                for (JsonElement dpEl : obj.getAsJsonArray("deniedPlayers")) {
                    try { portal.denyPlayer(UUID.fromString(dpEl.getAsString())); }
                    catch (IllegalArgumentException e) { plugin.getLogger().warning("[Storage] Invalid UUID in deniedPlayers for portal '" + id + "'"); }
                }
            }

            if (obj.has("lever")) {
                JsonObject leverObj = obj.getAsJsonObject("lever");
                World leverWorld = Bukkit.getWorld(leverObj.get("world").getAsString());
                if (leverWorld != null) {
                    portal.setLeverLocation(new Location(leverWorld,
                        leverObj.get("x").getAsInt(),
                        leverObj.get("y").getAsInt(),
                        leverObj.get("z").getAsInt()));
                }
            }

            return portal;
        } catch (Exception e) {
            plugin.getLogger().severe("[Storage] Failed to parse portal entry: " + e.getMessage());
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
