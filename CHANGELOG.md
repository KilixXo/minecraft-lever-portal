# Changelog

All notable changes to LeverPortal are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [1.1.0] — 2026-04-07

### Security
- **SEC-01** — JDBC URL properties are now parsed and URL-encoded before being appended to the connection URL, preventing JDBC parameter injection via `config.yml`.
- **SEC-01** — Database hostname and name are validated against a safe-character allowlist.
- **SEC-02** — `/portal access … allow|deny|remove|owner <player>` now warns the admin and aborts the operation when the target player has never joined the server (prevents silent UUID mismatches on online-mode servers).
- **SEC-03** — Default MySQL/MariaDB `properties` changed from `useSSL=false` to `useSSL=true` so database traffic is encrypted by default.
- **SEC-04** — Runtime portal/player costs are now stored in a separate `data.yml` file instead of `config.yml`, preventing accidental data loss on config reload.
- **SEC-05** — `portals` and `portalsByChunk` maps changed from `HashMap` to `ConcurrentHashMap` for thread-safe access.

### Bug Fixes
- **BUG-01** — Removed redundant `saveDefaultConfig()` + `reloadConfig()` calls from `EconomyManager.loadConfig()` that could discard in-memory state.
- **BUG-02** — Economy charge failure (insufficient funds) now applies a 1-second cooldown instead of immediately removing the player from the teleport guard, preventing the "insufficient funds" message from firing on every block-move event while the player stands inside the portal.
- **BUG-03** — `PortalCreationSession.addBlock()` now uses a `HashSet<String>` for O(1) duplicate detection instead of an O(n) linear scan.
- **BUG-04** — Removed the frame-block distance fallback from `Portal.containsPlayer()` that caused false-positive teleports when a player stood next to (but not inside) the portal frame.
- **BUG-05** — `LeverHandler.checkAndAssociateLever()` now checks portal ownership (or `leverportal.admin` permission) before associating a lever, preventing unauthorized toggle control over other players' portals.
- **BUG-06** — `JsonPortalStorage.saveAll()` now writes to a temporary file first, then atomically renames it to `portals.json`, preventing data corruption if the server crashes mid-write.
- **BUG-07** — `EconomyManager.setPortalCost(0)` now stores `0` explicitly (making the portal free) instead of removing the entry and falling back to the global cost.

### Performance
- **PERF-01** — `LeverHandler.checkAndAssociateLever()` now uses the chunk-based spatial index (`PortalRegistry.findPortalsNear()`) instead of iterating all portals — O(1) chunk lookup vs O(n×m) full scan.
- **PERF-02** — `Portal.addBlockLocation()` now sets a dirty flag instead of immediately running the BFS flood-fill interior computation; the BFS runs once when interior data is actually needed.
- **PERF-04** — MySQL and PostgreSQL storage backends now validate the connection with `connection.isValid(2)` before each operation and reconnect automatically if the connection has timed out (e.g., after the MySQL 8-hour idle timeout).
- **PERF-05** — `EconomyManager.chargeTeleport()` now uses a single-pass `tryRemoveDiamonds()` method instead of two separate inventory scans.

### Maintainability
- **MAINT-01** — Portal constants (`teleport_cooldown_ticks`, `min_blocks`, `max_blocks`, `min_link_distance`, `max_lever_distance`) are now read from `config.yml` at startup instead of being hardcoded.
- **MAINT-02** — `Portal.toJson()` now uses Gson (`JsonObject`/`JsonArray`) instead of a hand-rolled `StringBuilder`, fixing incomplete character escaping for portal names containing `\n`, `\t`, etc.
- **MAINT-03** — `Main` class refactored to handle only plugin lifecycle. Event handling extracted to `PortalEventListener` and `PortalInteractListener`; command handling extracted to `PortalCommandHandler` (Single Responsibility Principle).
- **MAINT-04** — `PortalConnection.hashCode()` changed from `(min*31 + max)` (integer overflow risk) to `h1 ^ h2` (standard commutative XOR hash).
- **MAINT-05** — Removed redundant `isEntityTypeAllowed(EntityType.PLAYER)` check from `PortalAccessManager.canPlayerUsePortal()`.

### Build
- Added [`build.gradle`](build.gradle) and [`settings.gradle`](settings.gradle) for Gradle builds using the Shadow plugin.
- The Shadow plugin shades and relocates all JDBC drivers under `com.portal.libs.*` to avoid classpath conflicts with other plugins.
- Build command: `./gradlew shadowJar` → `build/libs/LeverPortal-1.1.0.jar`

---

## [1.0.0] — Initial release

- Dual connected portals with lever activation
- Vertical and horizontal portal orientations
- BFS flood-fill interior detection
- Diamond-based economy for portal creation and teleportation
- Access control: PUBLIC / PRIVATE / WHITELIST / BLACKLIST modes
- Storage backends: JSON, SQLite, MySQL/MariaDB, PostgreSQL
- Chunk-based spatial index for fast portal lookup
