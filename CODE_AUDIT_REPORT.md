# Portal Plugin — Comprehensive Code Audit Report

**Date:** 2026-04-07  
**Auditor:** Kilo Code  
**Scope:** All Java source files + config resources  
**Mode:** Read-only analysis — no code was modified

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Bugs](#bugs)
3. [Security](#security)
4. [Performance](#performance)
5. [Maintainability](#maintainability)
6. [Summary Table](#summary-table)

---

## Executive Summary

The plugin is a well-structured Bukkit/Spigot plugin for creating lever-activated portals with a diamond economy. The codebase shows evidence of prior refactoring (comments like "H-3 fix", "M-1", "C-2 fix" etc.), meaning many issues have already been addressed. However, **17 distinct issues** remain across all four audit categories, including 2 Critical, 5 High, 5 Medium, and 5 Low severity findings.

---

## Bugs

### BUG-01 · Critical · `EconomyManager.java:44-45` — Double config reload on startup

**File:** [`EconomyManager.java`](src/main/java/com/portal/plugin/EconomyManager.java:44)  
**Severity:** Critical  
**Category:** Logic Error

**Description:**  
`loadConfig()` calls both `plugin.saveDefaultConfig()` and `plugin.reloadConfig()`. `saveDefaultConfig()` is already called in `Main.onEnable()` at line 38 before `EconomyManager` is constructed. The second `saveDefaultConfig()` inside `loadConfig()` is harmless, but `plugin.reloadConfig()` at line 45 **discards any in-memory config changes** that may have been applied between `saveDefaultConfig()` and `EconomyManager` construction. More critically, if `loadConfig()` is ever called again at runtime (e.g., via a reload command), it will wipe all in-memory portal cost overrides that were set via `/portal setcost` but not yet flushed to disk.

```java
// EconomyManager.java:43-46
public void loadConfig() {
    plugin.saveDefaultConfig();  // ← already called in Main.onEnable()
    plugin.reloadConfig();       // ← discards in-memory state
```

**Fix:**  
Remove both calls from `loadConfig()`. The config is already saved and loaded by `Main.onEnable()`. If a reload command is needed, implement it explicitly and save first.

---

### BUG-02 · Critical · `PortalRegistry.java:394-396` — Economy charge happens inside cooldown guard, but cooldown is not released on charge failure

**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:394)  
**Severity:** Critical  
**Category:** Logic Error / Race Condition

**Description:**  
In `handlePlayerEnteringPortal()`, the player is added to `recentlyTeleported` at line 355 (atomic check-and-set). If `economyManager.chargeTeleport()` returns `false` (insufficient funds), the method returns at line 395 without setting `teleported = true`. The `finally` block at line 425 then removes the player from `recentlyTeleported` — which is correct. **However**, the `return` statement at line 395 exits the entire method, bypassing the `break` at line 421 and the `lastPortalUsed` cleanup. This means if a player is standing in a portal they can't afford, every movement event fires a charge attempt, and the player receives the "insufficient funds" message on every block move until they leave the portal area.

```java
// PortalRegistry.java:393-396
if (!economyManager.chargeTeleport(player, portal.getId())) {
    return; // ← exits method; finally block removes cooldown immediately
}
// ... teleported = true is never set, so cooldown is removed immediately
```

**Fix:**  
Replace `return` with `continue` so the loop moves to the next portal (or exits naturally), allowing the `finally` block to correctly remove the cooldown. Add a short "charge-failed cooldown" to prevent message spam:

```java
if (!economyManager.chargeTeleport(player, portal.getId())) {
    // Add a brief cooldown to prevent message spam
    Bukkit.getScheduler().runTaskLater(plugin, () ->
        recentlyTeleported.remove(playerId), 20L); // 1 second
    teleported = true; // prevent immediate cooldown removal
    break;
}
```

---

### BUG-03 · High · `PortalRegistry.java:507-516` — O(n) duplicate check in `PortalCreationSession.addBlock()`

**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:507)  
**Severity:** High  
**Category:** Logic Error / Performance

**Description:**  
`PortalCreationSession.addBlock()` uses a linear scan over the `blocks` list to detect duplicates. This is O(n) per insertion. With `MAX_PORTAL_BLOCKS = 100`, this is tolerable but inconsistent with the rest of the codebase which uses `HashSet` for O(1) lookups. More importantly, the comparison at line 512 checks `loc.getWorld()` equality using `Objects.equals()`, but `Location.getWorld()` can return `null` for unloaded worlds — this is safe here, but the inconsistency with `Portal.addBlockLocation()` (which uses a string key) is a maintenance hazard.

**Fix:**  
Add a `Set<String>` to `PortalCreationSession` for O(1) duplicate detection, mirroring the pattern used in `Portal`.

---

### BUG-04 · High · `Portal.java:403-418` — `containsPlayer()` fallback uses frame-block distance, not interior

**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:403)  
**Severity:** High  
**Category:** Logic Error

**Description:**  
`containsPlayer()` first checks `interiorLocationKeys` (correct), then falls back to checking distance against **frame blocks** (line 412-416). This fallback is triggered when the interior is empty (e.g., a portal with fewer than 3 blocks). The fallback uses `PLAYER_DETECTION_RADIUS = 1.5` against frame blocks, which means a player standing *next to* a frame block (but not inside the portal) could be falsely detected as "inside" the portal and teleported. This is a gameplay correctness bug.

```java
// Portal.java:411-417 — fallback checks frame blocks, not interior
for (Location loc : this.blockLocations) {
    if (loc.getWorld() != null && loc.getWorld().equals(playerWorld)
        && loc.distance(playerLoc) < PLAYER_DETECTION_RADIUS) {
        return true; // ← false positive: player is near frame, not inside
    }
}
```

**Fix:**  
Remove the fallback entirely, or only use it when `interiorLocations` is empty AND the portal has fewer than 3 blocks (i.e., it's a degenerate portal). Log a warning when a portal has no interior.

---

### BUG-05 · High · `LeverHandler.java:107-130` — First matching portal wins; no ownership check for lever association

**File:** [`LeverHandler.java`](src/main/java/com/portal/plugin/LeverHandler.java:107)  
**Severity:** High  
**Category:** Logic Error / Security

**Description:**  
`checkAndAssociateLever()` iterates all portals and associates the lever with the **first** portal within `MAX_LEVER_DISTANCE`. There is no check that the player owns the portal they are associating the lever with. Any player who places a lever near someone else's portal can associate it and gain toggle control over that portal.

```java
// LeverHandler.java:122-128
leverToPortal.put(locKey(leverLoc), portal.getId());
portal.setLeverLocation(leverLoc);
player.sendMessage("§aLever associated with portal '" + portal.getId() + "'!");
return; // ← no ownership check performed
```

**Fix:**  
Add an ownership/admin check before associating:
```java
if (!player.hasPermission("leverportal.admin") && !accessManager.isOwner(portal, player.getUniqueId())) {
    player.sendMessage("§cYou don't own this portal!");
    return;
}
```

---

### BUG-06 · Medium · `JsonPortalStorage.java:45` — Non-atomic file write; data loss on crash

**File:** [`JsonPortalStorage.java`](src/main/java/com/portal/plugin/storage/JsonPortalStorage.java:45)  
**Severity:** Medium  
**Category:** Logic Error / Data Integrity

**Description:**  
`saveAll()` writes directly to `portals.json` using `FileWriter`. If the server crashes or the JVM is killed mid-write, the file will be partially written and corrupted. On next startup, `loadPortals()` will fail to parse the JSON and log a severe error, losing all portal data.

```java
// JsonPortalStorage.java:44-45
File portalFile = new File(dataFolder, "portals.json");
try (FileWriter writer = new FileWriter(portalFile)) { // ← direct overwrite
```

**Fix:**  
Write to a temporary file first, then atomically rename:
```java
File tempFile = new File(dataFolder, "portals.json.tmp");
try (FileWriter writer = new FileWriter(tempFile)) {
    // ... write content
}
Files.move(tempFile.toPath(), portalFile.toPath(),
    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
```

---

### BUG-07 · Medium · `EconomyManager.java:144-150` — `setPortalCost(0)` silently removes cost instead of setting it to zero

**File:** [`EconomyManager.java`](src/main/java/com/portal/plugin/EconomyManager.java:144)  
**Severity:** Medium  
**Category:** Logic Error

**Description:**  
`setPortalCost()` removes the portal from `portalCosts` when `cost <= 0`. This means setting a cost of `0` falls back to `globalTeleportCost` rather than making the portal free. An admin who runs `/portal setcost myportal 0` expecting to make it free will instead get the global cost applied.

```java
// EconomyManager.java:144-150
public void setPortalCost(String portalName, int cost) {
    if (cost <= 0) {
        portalCosts.remove(portalName); // ← cost=0 removes entry, falls back to global
    } else {
        portalCosts.put(portalName, cost);
    }
```

**Fix:**  
Store `0` explicitly to mean "free portal":
```java
if (cost < 0) {
    portalCosts.remove(portalName);
} else {
    portalCosts.put(portalName, cost); // 0 = explicitly free
}
```

---

## Security

### SEC-01 · Critical · `DatabaseConfig.java:128-136` — JDBC URL built with unsanitized config values (SSRF / injection risk)

**File:** [`DatabaseConfig.java`](src/main/java/com/portal/plugin/storage/DatabaseConfig.java:128)  
**Severity:** Critical  
**Category:** Injection / SSRF

**Description:**  
`buildMysqlUrl()` and `buildPostgresUrl()` concatenate `host`, `port`, `database`, and `properties` directly into the JDBC URL string without any sanitization. The `properties` field (line 133-135) is especially dangerous — it is appended verbatim as a query string. A malicious server operator who can edit `config.yml` could inject arbitrary JDBC parameters, including `allowLoadLocalInfile=true` (MySQL file read), `socketFactory` overrides, or other driver-specific exploits.

```java
// DatabaseConfig.java:133-135
if (properties != null && !properties.isEmpty()) {
    sb.append("?").append(properties); // ← raw config value injected into URL
}
```

**Fix:**  
Parse `properties` as a `Properties` object and use `URLEncoder` for each key-value pair. Alternatively, use a JDBC `DataSource` with explicit property setters instead of URL-based configuration. At minimum, validate that `host` matches a hostname/IP pattern and `database` contains only safe characters.

---

### SEC-02 · High · `Main.java:418` — `Bukkit.getOfflinePlayer(String)` creates a fake UUID for unknown players

**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:418)  
**Severity:** High  
**Category:** Authentication Flaw / Data Integrity

**Description:**  
`Bukkit.getOfflinePlayer(String name)` is called in the `allow`, `deny`, `remove`, and `owner` access subcommands (lines 418, 436, 454, 505). For players who have **never joined the server**, this method generates a **deterministic offline-mode UUID** based on the name. On online-mode servers, this UUID will never match the real player's UUID (which comes from Mojang). This means:
- Granting access to "Steve" on an online-mode server grants access to a UUID that no real player will ever have.
- The `targetOffline.getName()` check at line 420 returns `null` for truly unknown players, but the code falls back to `args[3]` (the raw name string) — so the operation silently "succeeds" with a wrong UUID.

```java
// Main.java:418-421
OfflinePlayer targetOffline = Bukkit.getOfflinePlayer(args[3]); // ← fake UUID for unknown players
UUID targetId = targetOffline.getUniqueId();
String targetName = targetOffline.getName() != null ? targetOffline.getName() : args[3];
accessManager.allowPlayer(portal, targetId); // ← wrong UUID stored
```

**Fix:**  
Use `Bukkit.getPlayerExact(name)` for online players, or query the Mojang API for offline players. At minimum, warn the admin if `targetOffline.getName() == null` (player never joined):
```java
if (targetOffline.getName() == null) {
    player.sendMessage("§cWarning: Player '" + args[3] + "' has never joined this server. UUID may be incorrect.");
}
```

---

### SEC-03 · High · `config.yml:44` — Default `properties` includes `useSSL=false` for MySQL

**File:** [`config.yml`](src/main/resources/config.yml:44)  
**Severity:** High  
**Category:** Security Misconfiguration

**Description:**  
The default `properties` value is `"useSSL=false&serverTimezone=UTC"`. This disables SSL for MySQL connections by default, meaning database credentials and portal data are transmitted in plaintext. This is a security misconfiguration that ships as the default.

```yaml
# config.yml:44
properties: "useSSL=false&serverTimezone=UTC"
```

**Fix:**  
Change the default to `"useSSL=true&serverTimezone=UTC"` or leave `properties` empty and document that SSL should be configured explicitly. Add a startup warning if `useSSL=false` is detected.

---

### SEC-04 · Medium · `EconomyManager.java:88-103` — Portal costs saved to `config.yml` (plaintext, world-readable)

**File:** [`EconomyManager.java`](src/main/java/com/portal/plugin/EconomyManager.java:88)  
**Severity:** Medium  
**Category:** Data Exposure

**Description:**  
`saveConfig()` writes portal costs and player-specific costs (keyed by UUID) to `config.yml`. This file is typically world-readable on Linux servers. Player UUIDs are considered semi-private identifiers. More importantly, mixing runtime data (portal costs) with static configuration (economy settings) in the same file creates a maintenance hazard — a server admin who edits `config.yml` and reloads will overwrite runtime cost changes.

**Fix:**  
Store runtime portal/player costs in a separate `data.yml` or in the chosen database backend (SQL/JSON), not in `config.yml`.

---

### SEC-05 · Medium · `PortalRegistry.java:36` — `portals` map is `HashMap` (not thread-safe), accessed from async contexts

**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:36)  
**Severity:** Medium  
**Category:** Race Condition

**Description:**  
`portals` is a plain `HashMap` (line 36). `portalsByChunk` is also a plain `HashMap` (line 43). Both are accessed from the main server thread (event handlers) and potentially from async storage operations. While Bukkit's event system is single-threaded, the `Bukkit.getScheduler().runTaskLater()` callback at line 416 runs on the main thread — but if any future async task (e.g., async save) reads `portals`, a `ConcurrentModificationException` is possible.

```java
// PortalRegistry.java:36-43
private final Map<String, Portal> portals = new HashMap<>();          // ← not thread-safe
private final Map<String, Set<Portal>> portalsByChunk = new HashMap<>(); // ← not thread-safe
```

**Fix:**  
Use `ConcurrentHashMap` for both maps, consistent with `recentlyTeleported` and `lastPortalUsed` which already use concurrent collections.

---

## Performance

### PERF-01 · High · `LeverHandler.java:108` — O(n) portal scan on every unrecognized lever click

**File:** [`LeverHandler.java`](src/main/java/com/portal/plugin/LeverHandler.java:108)  
**Severity:** High  
**Category:** Performance

**Description:**  
`checkAndAssociateLever()` iterates **all portals** every time a player right-clicks an unassociated lever. On a server with many portals, this is O(n) per lever click. The method calls `portal.getCenter()` for each portal (which iterates all interior/frame locations), making the effective complexity O(n × m) where m is the average portal size.

```java
// LeverHandler.java:108-130
for (String portalName : registry.getAllPortalNames()) { // ← O(n) over all portals
    Portal portal = registry.getPortal(portalName);
    Location portalCenter = portal.getCenter(); // ← O(m) per portal
    // ...
    if (portalCenter.distance(leverLoc) <= MAX_LEVER_DISTANCE) {
```

**Fix:**  
Use the existing chunk-based spatial index (`portalsByChunk`) in `PortalRegistry` to find nearby portals in O(1), then filter by distance. Expose a `findPortalsNear(Location, double)` method from `PortalRegistry`.

---

### PERF-02 · High · `Portal.java:452-459` — `computeInterior()` called on every `addBlockLocation()`

**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:452)  
**Severity:** High  
**Category:** Performance

**Description:**  
`addBlockLocation()` calls `computeInterior()` after every single block addition. During portal creation, a player right-clicks blocks one at a time, triggering `handlePortalInteraction()` → `session.addBlock()`. However, `addBlockLocation()` is also called during portal construction from storage (via the `Portal` constructor which calls `computeInterior()` once). The real issue is that if `addBlockLocation()` is ever called in a loop (e.g., during a future batch-add feature), the interior is recomputed n times instead of once.

More critically, `computeInterior()` allocates a 2D boolean array and runs a BFS flood-fill. For a 100-block portal, this is called 100 times during creation — 100 BFS runs instead of 1.

```java
// Portal.java:456-458
blockLocations.add(location.clone());
blockLocationKeys.add(key);
computeInterior(); // ← O(area) BFS called on every single block addition
```

**Fix:**  
Defer `computeInterior()` to an explicit `finalizePortal()` call, or batch additions with a dirty flag:
```java
private boolean interiorDirty = false;

public void addBlockLocation(Location location) {
    // ... add to collections
    interiorDirty = true; // mark dirty, don't recompute yet
}

public void ensureInteriorComputed() {
    if (interiorDirty) { computeInterior(); interiorDirty = false; }
}
```

---

### PERF-03 · Medium · `Portal.java:403-418` — `containsPlayer()` called on every `PlayerMoveEvent`

**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:403)  
**Severity:** Medium  
**Category:** Performance

**Description:**  
`handlePlayerEnteringPortal()` is called from `onPlayerMove()` for every block-to-block movement. It calls `findPortalsAt()` which uses the chunk index (efficient), but `containsPlayer()` still iterates all frame blocks in the fallback path (line 412-416). On a busy server with many players moving simultaneously, this adds up. The early-exit check at line 77 (`event.getFrom().getBlock().equals(to.getBlock())`) helps, but the fallback loop in `containsPlayer()` remains a concern.

**Fix:**  
Remove the fallback loop in `containsPlayer()` (see BUG-04). The chunk-indexed `isInteriorLocation()` check is sufficient and O(1).

---

### PERF-04 · Medium · `MySQLPortalStorage.java` / `SQLitePortalStorage.java` / `PostgreSQLPortalStorage.java` — Single shared `Connection` (no connection pool)

**File:** [`MySQLPortalStorage.java`](src/main/java/com/portal/plugin/storage/MySQLPortalStorage.java:38)  
**Severity:** Medium  
**Category:** Performance / Reliability

**Description:**  
All three SQL storage backends use a single `Connection` object. This is problematic because:
1. MySQL/PostgreSQL connections time out after idle periods (typically 8 hours for MySQL). After a timeout, all subsequent operations fail silently (the `connection == null` guard doesn't catch a stale connection).
2. There is no reconnection logic.
3. `saveAll()` is called on plugin disable (main thread), but if a future async save is added, the single connection will cause race conditions.

```java
// MySQLPortalStorage.java:38
private Connection connection; // ← single connection, no pool, no reconnect
```

**Fix:**  
Use HikariCP (a lightweight connection pool) or implement a `getConnection()` method that validates the connection with `connection.isValid(1)` and reconnects if needed.

---

### PERF-05 · Low · `EconomyManager.java:172-183` — `countDiamonds()` iterates full inventory twice per teleport

**File:** [`EconomyManager.java`](src/main/java/com/portal/plugin/EconomyManager.java:172)  
**Severity:** Low  
**Category:** Performance

**Description:**  
`chargeTeleport()` calls `countDiamonds()` (line 222) and then `removeDiamonds()` (line 232). Both methods iterate the player's inventory independently. This is two full inventory scans per teleport. While a player inventory is at most 41 slots, this is still unnecessary duplication.

**Fix:**  
Combine into a single `tryRemoveDiamonds(player, amount)` method that counts and removes in one pass, returning `false` if insufficient.

---

## Maintainability

### MAINT-01 · Medium · `PortalRegistry.java:27-30` — Constants not read from config at runtime

**File:** [`PortalRegistry.java`](src/main/java/com/portal/plugin/PortalRegistry.java:27)  
**Severity:** Medium  
**Category:** Maintainability / Configuration

**Description:**  
`TELEPORT_COOLDOWN_TICKS`, `MIN_PORTAL_BLOCKS`, `MAX_PORTAL_BLOCKS`, and `MIN_LINK_DISTANCE` are declared as `public static final` constants. The `config.yml` has corresponding entries (`portal.teleport_cooldown_ticks`, etc.), but these constants are **never read from config** — they are hardcoded. The config values are documented but ignored.

```java
// PortalRegistry.java:27-30
public static final int TELEPORT_COOLDOWN_TICKS = 40;   // ← hardcoded, config ignored
public static final int MIN_PORTAL_BLOCKS = 2;           // ← hardcoded, config ignored
public static final int MAX_PORTAL_BLOCKS = 100;         // ← hardcoded, config ignored
public static final double MIN_LINK_DISTANCE = 3.0;      // ← hardcoded, config ignored
```

Similarly, `LeverHandler.MAX_LEVER_DISTANCE = 10.0` and `Portal.PLAYER_DETECTION_RADIUS = 1.5` are hardcoded despite having config entries.

**Fix:**  
Read these values from config in `PortalRegistry` constructor (or a `loadConfig()` method), and remove the misleading config entries if they are not used, or actually use them.

---

### MAINT-02 · Medium · `Portal.java:491-545` — Hand-rolled JSON serialization (fragile, duplicated)

**File:** [`Portal.java`](src/main/java/com/portal/plugin/Portal.java:491)  
**Severity:** Medium  
**Category:** Maintainability

**Description:**  
`Portal.toJson()` manually builds a JSON string using `StringBuilder`. This is fragile — any field added to `Portal` must be manually added to `toJson()` and `JsonPortalStorage.parsePortal()`. The `escapeJson()` helper only escapes backslashes and double-quotes, missing other JSON special characters (`\n`, `\r`, `\t`, `\b`, `\f`, control characters). A portal name containing a newline character would produce invalid JSON.

```java
// Portal.java:478-481
private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\""); // ← incomplete escaping
}
```

**Fix:**  
Use the Gson library (already a dependency, used in `JsonPortalStorage`) for serialization. Create a `PortalSerializer` / `PortalDeserializer` using `JsonSerializer<Portal>` / `JsonDeserializer<Portal>`.

---

### MAINT-03 · Medium · `Main.java:28` — `Main` class violates Single Responsibility Principle

**File:** [`Main.java`](src/main/java/com/portal/plugin/Main.java:28)  
**Severity:** Medium  
**Category:** Maintainability / SOLID

**Description:**  
`Main` extends `JavaPlugin` AND implements `Listener`. It handles plugin lifecycle, command routing, AND event handling (3 event handlers). The command handler (`onCommand`) is 113 lines long and delegates to 8 private methods. While the delegation is good, the class still mixes plugin bootstrap, event listening, and command dispatch responsibilities.

**Fix:**  
Extract event handlers into a dedicated `PortalEventListener` class. Extract command handling into a `PortalCommandHandler` class. `Main` should only handle plugin lifecycle (enable/disable).

---

### MAINT-04 · Low · `PortalConnection.java:54-58` — `hashCode()` has high collision risk for similar portal names

**File:** [`PortalConnection.java`](src/main/java/com/portal/plugin/PortalConnection.java:54)  
**Severity:** Low  
**Category:** Maintainability

**Description:**  
The `hashCode()` implementation uses `Math.min(h1, h2) * 31 + Math.max(h1, h2)`. While this is commutative (correct for bidirectional connections), it has poor distribution for portal names with similar hash codes. For example, `PortalConnection("a","b")` and `PortalConnection("b","a")` produce the same hash (correct), but `PortalConnection("x","y")` and `PortalConnection("y","x")` also produce the same hash — which is the intended behavior. The issue is that `Math.min * 31 + Math.max` can overflow `int` for large hash values, producing negative results that are still valid but may cause unexpected behavior in some hash map implementations.

**Fix:**  
Use XOR for commutative hashing (standard approach):
```java
@Override
public int hashCode() {
    return portal1Id.hashCode() ^ portal2Id.hashCode();
}
```

---

### MAINT-05 · Low · `PortalAccessManager.java:5` — Unused import `EntityType` in access check

**File:** [`PortalAccessManager.java`](src/main/java/com/portal/plugin/PortalAccessManager.java:64)  
**Severity:** Low  
**Category:** Maintainability / Code Smell

**Description:**  
`canPlayerUsePortal()` checks `isEntityTypeAllowed(EntityType.PLAYER)` at line 73. This check is always `true` when `entityFilteringEnabled = false` (the default), and when `allowedEntityTypes` contains `PLAYER` (which it always does by default, line 47). The check is therefore redundant — if entity filtering is enabled and `PLAYER` is not in the allowed list, players can never use portals at all, which would be a misconfiguration. The check adds confusion without adding safety.

Additionally, `PortalAccessManager` imports `EntityType` but the entity filtering feature is only partially implemented — there is no event handler that actually prevents non-player entities from teleporting (the `PlayerMoveEvent` handler in `Main` only fires for players).

**Fix:**  
Remove the `isEntityTypeAllowed(EntityType.PLAYER)` check from `canPlayerUsePortal()`, or implement actual entity teleportation support with an `EntityMoveEvent` handler (Paper API) or `EntityPortalEvent`.

---

## Summary Table

| ID | Severity | File | Line | Category | Description |
|----|----------|------|------|----------|-------------|
| BUG-01 | **Critical** | `EconomyManager.java` | 44-45 | Logic Error | Double config reload discards in-memory state |
| BUG-02 | **Critical** | `PortalRegistry.java` | 394-396 | Logic/Race | `return` on charge failure causes message spam |
| BUG-03 | High | `PortalRegistry.java` | 507-516 | Logic/Perf | O(n) duplicate check in `PortalCreationSession` |
| BUG-04 | High | `Portal.java` | 403-418 | Logic | Frame-block fallback in `containsPlayer()` causes false positives |
| BUG-05 | High | `LeverHandler.java` | 107-130 | Logic/Security | No ownership check when associating lever to portal |
| BUG-06 | Medium | `JsonPortalStorage.java` | 45 | Data Integrity | Non-atomic file write risks data corruption on crash |
| BUG-07 | Medium | `EconomyManager.java` | 144-150 | Logic | `setPortalCost(0)` removes entry instead of setting free |
| SEC-01 | **Critical** | `DatabaseConfig.java` | 128-136 | Injection/SSRF | Unsanitized config values injected into JDBC URL |
| SEC-02 | High | `Main.java` | 418 | Auth Flaw | `getOfflinePlayer(String)` generates wrong UUID for unknown players |
| SEC-03 | High | `config.yml` | 44 | Misconfiguration | Default `useSSL=false` ships plaintext DB connections |
| SEC-04 | Medium | `EconomyManager.java` | 88-103 | Data Exposure | Runtime costs saved to world-readable `config.yml` |
| SEC-05 | Medium | `PortalRegistry.java` | 36 | Race Condition | `portals` and `portalsByChunk` are non-thread-safe `HashMap` |
| PERF-01 | High | `LeverHandler.java` | 108 | Performance | O(n×m) portal scan on every unrecognized lever click |
| PERF-02 | High | `Portal.java` | 452-459 | Performance | `computeInterior()` BFS called on every `addBlockLocation()` |
| PERF-03 | Medium | `Portal.java` | 403-418 | Performance | Frame-block fallback loop on every `PlayerMoveEvent` |
| PERF-04 | Medium | `MySQLPortalStorage.java` | 38 | Reliability | Single `Connection` with no pool or reconnect logic |
| PERF-05 | Low | `EconomyManager.java` | 172-183 | Performance | Double inventory scan per teleport |
| MAINT-01 | Medium | `PortalRegistry.java` | 27-30 | Config | Constants hardcoded; config entries are ignored |
| MAINT-02 | Medium | `Portal.java` | 491-545 | Maintainability | Hand-rolled JSON with incomplete escaping |
| MAINT-03 | Medium | `Main.java` | 28 | SOLID/SRP | `Main` mixes lifecycle, events, and command dispatch |
| MAINT-04 | Low | `PortalConnection.java` | 54-58 | Maintainability | `hashCode()` overflow risk for large hash values |
| MAINT-05 | Low | `PortalAccessManager.java` | 64-78 | Code Smell | Redundant entity type check; entity filtering not fully implemented |

---

## Severity Counts

| Severity | Count |
|----------|-------|
| Critical | 3 |
| High | 7 |
| Medium | 8 |
| Low | 4 |
| **Total** | **22** |

---

*This report is read-only. No source files were modified during this audit.*
