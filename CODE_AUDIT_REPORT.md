# Code Audit Report — Portal Plugin (Current Codebase)

**Date:** 2026-04-04  
**Auditor:** Senior Developer Review  
**Project:** Minecraft Portal Plugin (Bukkit/Spigot)

---

## Executive Summary

Comprehensive audit of 7 Java source files, 2 resource files, and 1 POM.  
**19 issues** found across 4 categories:

| Severity | Count |
|----------|-------|
| Critical | 3 |
| High | 7 |
| Medium | 9 |

The most pressing concerns are a **completely unimplemented `loadAllPortals()`** (all data lost on restart), a **broken cooldown-cleanup `finally` block** that always removes the cooldown, and **compilation errors** (`FileWriter` / `Files` / `Action` not imported in `PortalRegistry`).

---

## Critical Issues

### C-1 · `loadAllPortals()` is a no-op — all portal data lost on restart
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:354)  
**Lines:** 354-370  
**Category:** Bug

```java
public void loadAllPortals() {
    // ...
    String content = Files.readString(portalFile.toPath());
    // Simple JSON parsing - in production, use a proper JSON library
    plugin.getLogger().info("Portal data loaded from file.");
    // For now, just log that we attempted to load
}
```

`saveAllPortals()` writes valid JSON, but `loadAllPortals()` reads the file into a string and **does nothing with it**. Every server restart destroys all portals, connections, lever associations, and access-control data. Players lose diamonds already spent.

**Recommended fix:** Parse the JSON with Gson (bundled in Bukkit's runtime classpath) and reconstruct `portals` + `connections` maps:

```java
import com.google.gson.*;

public void loadAllPortals() {
    // ... read file ...
    JsonObject root = JsonParser.parseString(content).getAsJsonObject();
    for (JsonElement el : root.getAsJsonArray("portals")) {
        JsonObject obj = el.getAsJsonObject();
        // reconstruct Portal from id, orientation, blocks, owner, accessMode, etc.
    }
    for (JsonElement el : root.getAsJsonArray("connections")) {
        // reconstruct PortalConnection
    }
}
```

---

### C-2 · Cooldown `finally` block always removes the player — race condition bypass
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:295)  
**Lines:** 295-302  
**Category:** Bug / Race Condition

```java
} finally {
    if (!player.getLocation().equals(player.getLocation())) {
        // Player was teleported, cooldown is managed by scheduler
    } else {
        recentlyTeleported.remove(playerId);   // ← always executes
    }
}
```

`player.getLocation().equals(player.getLocation())` is **always `true`** (same object fetched twice in the same tick). So the `else` branch fires unconditionally, removing the player from the cooldown set immediately after a teleport. This **completely defeats the 2-second cooldown** and re-enables the rapid-teleport exploit.

**Recommended fix:** Track whether the teleport actually happened via a boolean flag:

```java
boolean teleported = false;
try {
    // ... portal loop ...
    player.teleport(targetLocation);
    teleported = true;
    // ...
} finally {
    if (!teleported) {
        recentlyTeleported.remove(playerId);
    }
}
```

---

### C-3 · `PortalRegistry` won't compile — missing imports
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:1)  
**Lines:** 1-14, 166, 311, 362  
**Category:** Bug (Compilation Error)

The file uses the following symbols without importing them:

| Symbol | Used at line | Missing import |
|--------|-------------|----------------|
| `Action` | 166 | `org.bukkit.event.block.Action` |
| `FileWriter` | 311 | `java.io.FileWriter` |
| `Files` | 362 | `java.nio.file.Files` |
| `IOException` | 346, 367 | `java.io.IOException` |

The code will **fail to compile** at all.

**Recommended fix:** Add the missing imports after line 14:

```java
import org.bukkit.event.block.Action;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
```

---

## High Severity Issues

### H-1 · `FileWriter` not closed on exception — resource leak / data corruption
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:308)  
**Lines:** 308-349  
**Category:** Bug / Resource Leak

```java
FileWriter writer = new FileWriter(portalFile);   // line 311
// ... many writes ...
writer.close();                                    // line 342
```

If any `writer.write()` throws `IOException`, the `writer.close()` on line 342 is skipped. The file handle leaks and the partially written file may corrupt the save.

**Recommended fix:** Use try-with-resources:

```java
try (FileWriter writer = new FileWriter(portalFile)) {
    // ...
} catch (IOException e) {
    plugin.getLogger().severe("Failed to save portals: " + e.getMessage());
}
```

---

### H-2 · `OfflinePlayer.getName()` can return `null` — NPE in chat messages
**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:382)  
**Lines:** 386, 402, 418, 425, 433, 441, 458  
**Category:** Bug / Null Handling

```java
OfflinePlayer targetOffline = Bukkit.getOfflinePlayer(args[3]);
// ...
.replace("%player%", targetOffline.getName())   // getName() may be null
```

`Bukkit.getOfflinePlayer(String)` creates a profile for any name. If that player has **never joined the server**, `getName()` returns `null`, causing `NullPointerException` in `String.replace()`.

**Recommended fix:**

```java
String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
```

---

### H-3 · `Location` as `HashMap` key — unstable hash / missed lookups
**File:** [`LeverHandler.java`](src/main/java/com/portal/plugin/LeverHandler.java:23)  
**Lines:** 23, 49, 110, 126  
**Category:** Bug

```java
private final Map<Location, String> leverToPortal = new HashMap<>();
```

Bukkit's `Location` has mutable state (x/y/z/yaw/pitch) and its `hashCode()` includes floating-point fields. Two `Location` objects for the **exact same block** can have slightly different floating-point values, causing `HashMap.get()` to miss. This means lever-to-portal associations silently break.

**Recommended fix:** Key the map by a serialised block-coordinate string:

```java
private final Map<String, String> leverToPortal = new HashMap<>();

private static String locKey(Location loc) {
    return loc.getWorld().getName() + "," + loc.getBlockX() + ","
         + loc.getBlockY() + "," + loc.getBlockZ();
}
```

---

### H-4 · No permission check on `/portal remove`
**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:266)  
**Lines:** 266-273  
**Category:** Security

```java
private void handleRemoveCommand(Player player, String name) {
    if (portalRegistry.getPortal(name) != null) {
        portalRegistry.removePortal(name);
        // ...
    }
}
```

Any player with `leverportal.use` can delete **any** portal — no ownership or admin check.

**Recommended fix:**

```java
Portal portal = portalRegistry.getPortal(name);
if (portal == null) { /* not found */ return; }
boolean isAdmin = player.hasPermission("leverportal.admin");
boolean isOwner = accessManager.isOwner(portal, player.getUniqueId());
if (!isAdmin && !isOwner) {
    player.sendMessage("§cYou can only remove portals you own.");
    return;
}
portalRegistry.removePortal(name);
```

---

### H-5 · No permission check on `/portal link`
**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:235)  
**Lines:** 250-257 (ownership check exists but `leverportal.link` not checked)  
**Category:** Security

`plugin.yml` defines `leverportal.link` permission, but `handleLinkCommand()` never checks it. Any player with `leverportal.use` who happens to own two portals can link them, but additionally the ownership check itself can be bypassed because the owner UUID is only set when a portal is created through the normal flow — loaded portals (once loading works) could have `null` owners.

**Recommended fix:** Add `leverportal.link` check:

```java
if (!player.hasPermission("leverportal.link") && !player.hasPermission("leverportal.admin")) {
    player.sendMessage("§cYou don't have permission to link portals.");
    return;
}
```

---

### H-6 · `containsPlayer()` calls `Location.distance()` across worlds — IllegalArgumentException
**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:222)  
**Lines:** 222-229  
**Category:** Bug

```java
public boolean containsPlayer(Player player) {
    Location playerLoc = player.getLocation();
    for (Location loc : this.blockLocations) {
        if (loc.distance(playerLoc) < 1.5) {   // throws if different worlds
            return true;
        }
    }
    return false;
}
```

`Location.distance()` throws `IllegalArgumentException` when the two locations are in different worlds. If a portal is in the Nether and the player is in the Overworld, this crashes.

**Recommended fix:** Guard with world check:

```java
if (loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())
    && loc.distance(playerLoc) < 1.5) {
```

---

### H-7 · Lever associations & creation sessions not persisted
**File:** [`LeverHandler.java`](src/main/java/com/portal/plugin/LeverHandler.java:23)  
**Lines:** 23  
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:26)  
**Lines:** 26  
**Category:** Bug / Data Loss

`leverToPortal` in `LeverHandler` and `creationSessions` in `PortalRegistry` are only held in memory. On server restart:

- All lever associations disappear (even if portal loading were fixed).
- Active creation sessions are lost (player has already paid diamonds via economy).

**Recommended fix:**

- Save lever associations alongside portal data in `portals.json`.
- Either persist creation sessions or refund diamonds on `onDisable()`.

---

## Medium Severity Issues

### M-1 · No limit on portal block count
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:393)  
**Lines:** 393-403  
**Category:** Performance / Griefing

`PortalCreationSession.addBlock()` has no cap. A malicious player can add thousands of blocks, inflating memory and slowing `findPortalsAt()`.

**Recommended fix:** Add `MAX_BLOCKS = 100` constant; reject additions beyond the limit.

---

### M-2 · O(n) portal scan on every `PlayerMoveEvent`
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:77)  
**Lines:** 77-85  
**Category:** Performance

`findPortalsAt()` iterates every portal on every block-change move event. With 100+ portals and 50+ players, this becomes a hot-path bottleneck.

**Recommended fix:** Maintain a `Map<String, Set<Portal>>` keyed by chunk coordinate for O(1) lookup.

---

### M-3 · `PortalConnection.hashCode()` has high collision rate
**File:** [`PortalConnection.java`](src/main/java/com/portal/plugin/PortalConnection.java:54)  
**Lines:** 54-57  
**Category:** Performance

```java
public int hashCode() {
    return portal1Id.hashCode() + portal2Id.hashCode();
}
```

Simple addition is commutative (good for bidirectional equality), but produces many hash collisions for similar strings, degrading `HashSet` performance.

**Recommended fix:**

```java
public int hashCode() {
    int h1 = portal1Id.hashCode();
    int h2 = portal2Id.hashCode();
    return (Math.min(h1, h2) * 31) + Math.max(h1, h2);
}
```

---

### M-4 · `isValidConnection()` may NPE on `getWorld()`
**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:135)  
**Lines:** 135  
**Category:** Bug / Null Handling

```java
if (!sourceCenter.getWorld().equals(targetCenter.getWorld())) {
```

`getCenter()` can return a Location with a `null` world (if `blockLocations` elements have unloaded worlds but `blockLocations.get(0).getWorld()` returned non-null while another block's world is null, the returned center still uses the first world). However, `sourceCenter.getWorld()` could itself be null if the world unloads between the null check in `getCenter()` and this line.

**Recommended fix:**

```java
World srcWorld = sourceCenter.getWorld();
World tgtWorld = targetCenter.getWorld();
if (srcWorld == null || tgtWorld == null || !srcWorld.equals(tgtWorld)) {
    return false;
}
```

---

### M-5 · `toJson()` does not escape special characters — potential JSON corruption
**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:275)  
**Lines:** 275-332  
**Category:** Security / Data Integrity

```java
sb.append("\"id\":\"").append(this.id).append("\",");
sb.append("\"world\":\"").append(loc.getWorld().getName()).append("\",");
```

If a portal ID or world name contains `"` or `\`, the JSON output is malformed. Portal names are validated by `isValidPortalName()`, but world names are not under the plugin's control (e.g., a world named `my"world`).

**Recommended fix:** Use Gson for serialisation, or manually escape strings:

```java
private String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
}
```

---

### M-6 · `toJson()` NPE when `leverLocation.getWorld()` is null
**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:310)  
**Lines:** 310  
**Category:** Bug / Null Handling

```java
sb.append("\"world\":\"").append(leverLocation.getWorld().getName()).append("\",");
```

If the lever's world is unloaded, `getWorld()` returns `null` → NPE during save → **entire save fails**, losing all portal data.

Same issue on line 321 for block locations.

**Recommended fix:** Null-guard world access and skip entries with unloaded worlds, or cache world names as strings at creation time.

---

### M-7 · Redundant access modes — `PRIVATE` and `WHITELIST` are identical
**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:159)  
**Lines:** 165-184  
**Category:** Maintainability

```java
case PRIVATE:
    return allowedPlayers.contains(playerId);   // identical to WHITELIST
case WHITELIST:
    return allowedPlayers.contains(playerId);
```

Also, `PUBLIC` and `BLACKLIST` are identical. The enum suggests 4 distinct modes but only 2 behaviours exist.

**Recommended fix:** `PRIVATE` should likely mean "owner only" (return `false` for non-owners, ignoring the allowed list), or merge the duplicate modes and document the difference clearly.

---

### M-8 · `handleSetCostCommand` accepts negative costs
**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:293)  
**Lines:** 300-301  
**Category:** Bug / Input Validation

```java
int cost = Integer.parseInt(costStr);
economyManager.setPortalCost(portalName, cost);
```

A negative value is accepted. In `setPortalCost()`, cost ≤ 0 removes the override, which silently falls back to global cost rather than rejecting invalid input. An admin expecting to set cost to 0 (free) ends up removing the override instead.

**Recommended fix:**

```java
if (cost < 0) {
    player.sendMessage("§cCost must be zero or positive.");
    return;
}
```

---

### M-9 · Hardcoded magic numbers
**File:** Multiple  
**Category:** Maintainability

| Value | Location | Meaning |
|-------|----------|---------|
| `10` | [`LeverHandler.java:107`](src/main/java/com/portal/plugin/LeverHandler.java:107) | Max lever-to-portal distance |
| `40L` | [`PortalRegistry.java:291`](src/main/java/com/portal/plugin/PortalRegistry.java:291) | Teleport cooldown ticks |
| `1.5` | [`Portal.java:225`](src/main/java/com/portal/plugin/Portal.java:225) | Player-in-portal distance threshold |
| `3` | [`PortalRegistry.java:141`](src/main/java/com/portal/plugin/PortalRegistry.java:141) | Minimum portal-to-portal distance |
| `2` | [`PortalRegistry.java:193`](src/main/java/com/portal/plugin/PortalRegistry.java:193) | Minimum blocks per portal |

**Recommended fix:** Move to `config.yml` or declare as named constants.

---

## Maintainability Observations (Non-Scored)

| Observation | Location |
|-------------|----------|
| **God class** — `Main.java` (488 lines) handles plugin lifecycle, commands, and events. Extract a `CommandHandler`. | [`Main.java`](src/main/java/com/portal/plugin/Main.java) |
| **Long switch** — `handleAccessCommand()` is 130 lines of repeated patterns. Each case follows the same owner-check → args-check → action pattern. | [`Main.java:334`](src/main/java/com/portal/plugin/Main.java:334) |
| **`PortalAccessManager` is a pass-through** — every method simply delegates to `Portal`. This violates YAGNI and adds an unnecessary layer. | [`PortalAccessManager.java`](src/main/java/com/portal/plugin/PortalAccessManager.java) |
| **No unit tests** — complex access-control logic, economy calculations, and teleport flows have zero test coverage. | — |
| **Manual JSON serialisation** — error-prone and hard to maintain. Gson is on the classpath. | [`Portal.toJson()`](src/main/java/com/portal/plugin/Portal.java:275) |
| **Inconsistent message handling** — some messages are fetched from config, others are hardcoded strings with `§` codes. | [`Main.java`](src/main/java/com/portal/plugin/Main.java) |

---

## Priority Remediation Plan

### Immediate (Blocks Production)
1. **C-3** — Add missing imports so the project compiles.
2. **C-1** — Implement `loadAllPortals()` to prevent data loss.
3. **C-2** — Fix the `finally` block so the teleport cooldown actually works.
4. **H-1** — Use try-with-resources in `saveAllPortals()`.

### Before Release
5. **H-2** — Guard `OfflinePlayer.getName()` against `null`.
6. **H-3** — Replace `Location` keys in lever map with string keys.
7. **H-4** — Add ownership/admin check to `/portal remove`.
8. **H-5** — Check `leverportal.link` permission.
9. **H-6** — Guard `distance()` against cross-world calls.
10. **H-7** — Persist lever associations; handle creation-session refunds.

### Short-Term Tech Debt
11. **M-1** through **M-9** — Input validation, spatial indexing, JSON escaping, magic numbers, etc.

### Long-Term
12. Refactor `Main.java` into command handler classes.
13. Replace manual JSON with Gson.
14. Add unit and integration tests.
15. Eliminate the `PortalAccessManager` pass-through or give it real logic (caching, event auditing).

---

## Conclusion

The codebase shows evidence of recent fixes (ConcurrentHashMap cooldown set, null-check on `event.getTo()`, UUID parsing try-catch, portal-name validation, etc.). However, **three critical issues remain** that prevent production use:

1. Portal data is written to disk but **never loaded back** — complete data loss every restart.
2. The teleport cooldown is **immediately cleared** by a faulty `finally` block.
3. Missing imports mean the project **does not compile**.

**Estimated effort:** 2-3 days for critical + high issues; 1 week for full remediation including tests.

**Risk level:** HIGH — do not deploy until C-1 through C-3 and H-1 through H-4 are resolved.
