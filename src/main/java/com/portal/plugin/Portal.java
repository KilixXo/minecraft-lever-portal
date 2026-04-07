package com.portal.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portal - Represents a single portal with its configuration.
 *
 * A portal is a frame of arbitrary blocks (1-block thick) chosen by the player.
 * Teleportation occurs when a player walks into the INTERIOR space enclosed by the frame.
 */
public class Portal {

    public static final double PLAYER_DETECTION_RADIUS = 1.5;

    // MAINT-02: shared Gson instance for JSON serialization
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String id;
    private final Orientation orientation;
    private final List<Location> blockLocations;
    private final Set<String> blockLocationKeys = new HashSet<>();
    private final Set<String> interiorLocationKeys = new HashSet<>();
    private final List<Location> interiorLocations = new ArrayList<>();
    private boolean active = false;
    private Location leverLocation;

    // PERF-02: dirty flag — defer computeInterior() until explicitly needed
    private boolean interiorDirty = false;

    // Access control fields
    private UUID ownerId;
    private AccessMode accessMode = AccessMode.PUBLIC;
    private final Set<UUID> allowedPlayers = new HashSet<>();
    private final Set<UUID> deniedPlayers = new HashSet<>();

    public enum Orientation {
        VERTICAL,
        HORIZONTAL
    }

    /**
     * Access modes with distinct behaviours:
     * <ul>
     *   <li>PUBLIC  — everyone except explicitly denied players</li>
     *   <li>PRIVATE — owner only</li>
     *   <li>WHITELIST — only explicitly allowed players (+ owner)</li>
     *   <li>BLACKLIST — everyone except explicitly denied players</li>
     * </ul>
     */
    public enum AccessMode {
        PUBLIC,
        PRIVATE,
        WHITELIST,
        BLACKLIST
    }

    public Portal(String id, Orientation orientation, List<Location> blockLocations) {
        this.id = id;
        this.orientation = orientation;
        this.blockLocations = new ArrayList<>(blockLocations);
        for (Location loc : blockLocations) {
            blockLocationKeys.add(locationKey(loc));
        }
        computeInterior();
    }

    public Portal(String id, Orientation orientation, List<Location> blockLocations, UUID ownerId) {
        this.id = id;
        this.orientation = orientation;
        this.blockLocations = new ArrayList<>(blockLocations);
        this.ownerId = ownerId;
        for (Location loc : blockLocations) {
            blockLocationKeys.add(locationKey(loc));
        }
        computeInterior();
    }

    /**
     * Compute the interior (passable) locations enclosed by the frame blocks.
     *
     * Strategy: find the bounding box of all frame blocks, then for each block
     * inside the bounding box that is NOT a frame block, check if it is fully
     * enclosed by frame blocks (i.e. a flood-fill from outside the bounding box
     * cannot reach it). We use a simpler approach: for each candidate interior
     * block, check that at least one frame block is on each side of it along the
     * two axes perpendicular to the portal's thin axis.
     *
     * For VERTICAL portals: the frame is thin along one horizontal axis (X or Z).
     * For HORIZONTAL portals: the frame is thin along the Y axis.
     */
    private void computeInterior() {
        interiorLocationKeys.clear();
        interiorLocations.clear();
        interiorDirty = false;

        if (blockLocations.size() < 3) {
            return; // Need at least 3 blocks to form a frame with interior
        }

        World world = blockLocations.get(0).getWorld();
        if (world == null) return;

        // Find bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (Location loc : blockLocations) {
            int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
            minX = Math.min(minX, bx); maxX = Math.max(maxX, bx);
            minY = Math.min(minY, by); maxY = Math.max(maxY, by);
            minZ = Math.min(minZ, bz); maxZ = Math.max(maxZ, bz);
        }

        // Determine the thin axis (the axis where the frame is 1-block thick)
        int spanX = maxX - minX;
        int spanZ = maxZ - minZ;

        if (orientation == Orientation.VERTICAL) {
            // Vertical portal: thin along X or Z
            if (spanX <= spanZ) {
                // Thin along X — portal plane is YZ
                computeInteriorForPlane(world, minX, minY, minZ, maxX, maxY, maxZ, 'X');
            } else {
                // Thin along Z — portal plane is XY
                computeInteriorForPlane(world, minX, minY, minZ, maxX, maxY, maxZ, 'Z');
            }
        } else {
            // Horizontal portal: thin along Y — portal plane is XZ
            computeInteriorForPlane(world, minX, minY, minZ, maxX, maxY, maxZ, 'Y');
        }
    }

    /**
     * Ensure the interior is up-to-date. Call this before any read of interior data
     * if blocks may have been added since the last computation.
     */
    public void ensureInteriorComputed() {
        if (interiorDirty) {
            computeInterior();
        }
    }

    /**
     * Find interior blocks for a portal that is thin along the given axis.
     * The interior is the set of blocks inside the bounding box that are enclosed
     * by frame blocks on the two in-plane axes.
     */
    private void computeInteriorForPlane(World world,
                                          int minX, int minY, int minZ,
                                          int maxX, int maxY, int maxZ,
                                          char thinAxis) {
        // We use a flood-fill approach: mark all cells in the bounding box (excluding frame),
        // then flood-fill from the border. Anything not reached is interior.

        int a0, a1, b0, b1, t0, t1;
        // a, b = the two in-plane axes; t = thin axis
        switch (thinAxis) {
            case 'X':
                // Plane is YZ, thin along X
                a0 = minY; a1 = maxY; // a = Y
                b0 = minZ; b1 = maxZ; // b = Z
                t0 = minX; t1 = maxX; // t = X
                break;
            case 'Z':
                // Plane is XY, thin along Z
                a0 = minX; a1 = maxX; // a = X
                b0 = minY; b1 = maxY; // b = Y
                t0 = minZ; t1 = maxZ; // t = Z
                break;
            case 'Y':
            default:
                // Plane is XZ, thin along Y
                a0 = minX; a1 = maxX; // a = X
                b0 = minZ; b1 = maxZ; // b = Z
                t0 = minY; t1 = maxY; // t = Y
                break;
        }

        int aSize = a1 - a0 + 1;
        int bSize = b1 - b0 + 1;

        // Build a grid of the portal plane: true = frame block present
        boolean[][] isFrame = new boolean[aSize][bSize];
        for (Location loc : blockLocations) {
            int a, b;
            switch (thinAxis) {
                case 'X': a = loc.getBlockY() - a0; b = loc.getBlockZ() - b0; break;
                case 'Z': a = loc.getBlockX() - a0; b = loc.getBlockY() - b0; break;
                case 'Y':
                default:  a = loc.getBlockX() - a0; b = loc.getBlockZ() - b0; break;
            }
            if (a >= 0 && a < aSize && b >= 0 && b < bSize) {
                isFrame[a][b] = true;
            }
        }

        // Flood-fill from edges to find exterior cells
        boolean[][] visited = new boolean[aSize][bSize];
        java.util.Queue<int[]> queue = new java.util.LinkedList<>();

        // Seed the flood-fill from all border cells that are NOT frame
        for (int a = 0; a < aSize; a++) {
            for (int b = 0; b < bSize; b++) {
                if ((a == 0 || a == aSize - 1 || b == 0 || b == bSize - 1) && !isFrame[a][b]) {
                    visited[a][b] = true;
                    queue.add(new int[]{a, b});
                }
            }
        }

        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] d : dirs) {
                int na = cell[0] + d[0];
                int nb = cell[1] + d[1];
                if (na >= 0 && na < aSize && nb >= 0 && nb < bSize
                    && !visited[na][nb] && !isFrame[na][nb]) {
                    visited[na][nb] = true;
                    queue.add(new int[]{na, nb});
                }
            }
        }

        // The thin-axis coordinate(s) for interior blocks
        // For a 1-block-thick frame, use all thin-axis values in range
        for (int tVal = t0; tVal <= t1; tVal++) {
            for (int a = 0; a < aSize; a++) {
                for (int b = 0; b < bSize; b++) {
                    if (!isFrame[a][b] && !visited[a][b]) {
                        // This cell is interior!
                        int wx, wy, wz;
                        switch (thinAxis) {
                            case 'X': wx = tVal;      wy = a + a0; wz = b + b0; break;
                            case 'Z': wx = a + a0;    wy = b + b0; wz = tVal;   break;
                            case 'Y':
                            default:  wx = a + a0;    wy = tVal;   wz = b + b0; break;
                        }
                        Location interiorLoc = new Location(world, wx, wy, wz);
                        String key = locationKey(interiorLoc);
                        if (!interiorLocationKeys.contains(key)) {
                            interiorLocationKeys.add(key);
                            interiorLocations.add(interiorLoc);
                        }
                    }
                }
            }
        }
    }

    /**
     * Generate a unique key for a location.
     */
    private String locationKey(Location loc) {
        World w = loc.getWorld();
        String worldName = (w != null) ? w.getName() : "unknown";
        return worldName + "," + loc.getBlockX() + "," +
               loc.getBlockY() + "," + loc.getBlockZ();
    }

    public String getId() {
        return this.id;
    }

    public Orientation getOrientation() {
        return this.orientation;
    }

    public boolean isActive() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
        updatePortalBlocks();
    }

    public List<Location> getBlockLocations() {
        return new ArrayList<>(this.blockLocations);
    }

    /**
     * Get the computed interior (passable) locations inside the portal frame.
     * Ensures interior is up-to-date before returning.
     */
    public List<Location> getInteriorLocations() {
        ensureInteriorComputed();
        return new ArrayList<>(this.interiorLocations);
    }

    public Location getLeverLocation() {
        return leverLocation;
    }

    public void setLeverLocation(Location leverLocation) {
        this.leverLocation = leverLocation;
    }

    // Access control methods

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public AccessMode getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(AccessMode accessMode) {
        this.accessMode = accessMode;
    }

    public boolean isOwner(UUID playerId) {
        return ownerId != null && ownerId.equals(playerId);
    }

    public void allowPlayer(UUID playerId) {
        allowedPlayers.add(playerId);
        deniedPlayers.remove(playerId);
    }

    public void denyPlayer(UUID playerId) {
        deniedPlayers.add(playerId);
        allowedPlayers.remove(playerId);
    }

    public void removePlayerAccess(UUID playerId) {
        allowedPlayers.remove(playerId);
        deniedPlayers.remove(playerId);
    }

    public boolean isPlayerAllowed(UUID playerId) {
        return allowedPlayers.contains(playerId);
    }

    public boolean isPlayerDenied(UUID playerId) {
        return deniedPlayers.contains(playerId);
    }

    public Set<UUID> getAllowedPlayers() {
        return new HashSet<>(allowedPlayers);
    }

    public Set<UUID> getDeniedPlayers() {
        return new HashSet<>(deniedPlayers);
    }

    /**
     * Check if a player has access to use this portal.
     */
    public boolean canPlayerUse(UUID playerId) {
        if (isOwner(playerId)) {
            return true;
        }

        switch (accessMode) {
            case PUBLIC:
                return !deniedPlayers.contains(playerId);
            case PRIVATE:
                return false;
            case WHITELIST:
                return allowedPlayers.contains(playerId);
            case BLACKLIST:
                return !deniedPlayers.contains(playerId);
            default:
                return false;
        }
    }

    /**
     * Get the center location of this portal (center of the interior if available, else center of frame).
     */
    public Location getCenter() {
        ensureInteriorComputed();
        List<Location> locs = interiorLocations.isEmpty() ? blockLocations : interiorLocations;
        if (locs.isEmpty()) {
            return null;
        }

        World world = locs.get(0).getWorld();
        if (world == null) {
            return null;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Location loc : locs) {
            minX = Math.min(minX, loc.getX());
            maxX = Math.max(maxX, loc.getX());
            minY = Math.min(minY, loc.getY());
            maxY = Math.max(maxY, loc.getY());
            minZ = Math.min(minZ, loc.getZ());
            maxZ = Math.max(maxZ, loc.getZ());
        }

        return new Location(world,
                           (minX + maxX) / 2.0 + 0.5,
                           (minY + maxY) / 2.0,
                           (minZ + maxZ) / 2.0 + 0.5);
    }

    /**
     * Check if a player is inside this portal's interior space.
     *
     * BUG-04 / PERF-03 fix: removed the frame-block distance fallback that caused
     * false-positive teleports when a player stood next to (but not inside) the frame.
     * The interior key lookup is O(1) and sufficient.
     */
    public boolean containsPlayer(Player player) {
        ensureInteriorComputed();
        Location playerLoc = player.getLocation();
        String playerKey = locationKey(playerLoc);
        return interiorLocationKeys.contains(playerKey);
    }

    /**
     * Check if a location is part of this portal's frame blocks.
     */
    public boolean containsLocation(Location location) {
        return blockLocationKeys.contains(locationKey(location));
    }

    /**
     * Check if a location is inside this portal's interior (passable space).
     */
    public boolean isInteriorLocation(Location location) {
        ensureInteriorComputed();
        return interiorLocationKeys.contains(locationKey(location));
    }

    /**
     * Update portal blocks visual state based on active status.
     */
    private void updatePortalBlocks() {
        for (Location loc : blockLocations) {
            Block block = loc.getBlock();
            if (active) {
                if (block.getType() != Material.AIR && block.getType() != Material.LEVER) {
                    // Portal is active — could add particle effects here
                }
            }
        }
    }

    /**
     * Add a block location to this portal.
     *
     * PERF-02 fix: marks interior as dirty instead of recomputing immediately.
     * Call {@link #ensureInteriorComputed()} (or {@link #getInteriorLocations()}) when
     * the interior data is actually needed.
     */
    public void addBlockLocation(Location location) {
        String key = locationKey(location);
        if (!blockLocationKeys.contains(key)) {
            blockLocations.add(location.clone());
            blockLocationKeys.add(key);
            // Mark dirty — defer BFS until interior is actually needed
            interiorDirty = true;
        }
    }

    /**
     * Get the number of blocks in this portal.
     */
    public int getBlockCount() {
        return blockLocations.size();
    }

    /**
     * Get the number of interior blocks in this portal.
     */
    public int getInteriorBlockCount() {
        ensureInteriorComputed();
        return interiorLocations.size();
    }

    // ── JSON serialization (MAINT-02: uses Gson instead of hand-rolled StringBuilder) ──

    /**
     * Convert portal to JSON string for saving.
     * Uses Gson for correct escaping of all special characters.
     */
    public String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", this.id);
        obj.addProperty("orientation", this.orientation.name());
        obj.addProperty("active", this.active);

        if (ownerId != null) {
            obj.addProperty("owner", ownerId.toString());
        }

        obj.addProperty("accessMode", accessMode.name());

        JsonArray allowedArr = new JsonArray();
        for (UUID uuid : allowedPlayers) {
            allowedArr.add(uuid.toString());
        }
        obj.add("allowedPlayers", allowedArr);

        JsonArray deniedArr = new JsonArray();
        for (UUID uuid : deniedPlayers) {
            deniedArr.add(uuid.toString());
        }
        obj.add("deniedPlayers", deniedArr);

        if (leverLocation != null) {
            JsonObject leverObj = new JsonObject();
            World w = leverLocation.getWorld();
            leverObj.addProperty("world", w != null ? w.getName() : "unknown");
            leverObj.addProperty("x", leverLocation.getBlockX());
            leverObj.addProperty("y", leverLocation.getBlockY());
            leverObj.addProperty("z", leverLocation.getBlockZ());
            obj.add("lever", leverObj);
        }

        JsonArray blocksArr = new JsonArray();
        for (Location loc : blockLocations) {
            JsonObject blockObj = new JsonObject();
            World w = loc.getWorld();
            blockObj.addProperty("world", w != null ? w.getName() : "unknown");
            blockObj.addProperty("x", loc.getBlockX());
            blockObj.addProperty("y", loc.getBlockY());
            blockObj.addProperty("z", loc.getBlockZ());
            blocksArr.add(blockObj);
        }
        obj.add("blocks", blocksArr);

        return GSON.toJson(obj);
    }

    @Override
    public String toString() {
        return "Portal{" +
               "id='" + this.id + '\'' +
               ", orientation=" + this.orientation +
               ", active=" + this.active +
               ", frameBlocks=" + blockLocations.size() +
               ", interiorBlocks=" + interiorLocations.size() +
               '}';
    }
}
