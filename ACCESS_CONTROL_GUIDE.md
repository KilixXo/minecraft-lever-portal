# Portal Access Control System - Implementation Guide

## Overview

This document describes the comprehensive access control system that has been added to the Portal Plugin. The system allows portal owners to control who can use their portals and provides administrators with global entity filtering capabilities.

## Features Implemented

### 1. Portal Ownership
- Each portal now has an owner (the player who created it)
- Owners have full control over their portal's access settings
- Ownership can be transferred to other players

### 2. Access Modes
Four access modes are available for each portal:

- **PUBLIC** (default): Everyone can use the portal unless explicitly denied
- **PRIVATE**: Only the owner and explicitly allowed players can use
- **WHITELIST**: Only explicitly allowed players can use (stricter than PRIVATE)
- **BLACKLIST**: Everyone can use except explicitly denied players

### 3. Player Access Lists
- **Allowed Players**: Players who are granted access to use the portal
- **Denied Players**: Players who are blocked from using the portal
- Access lists work in conjunction with the access mode

### 4. Entity Filtering (Global)
- Administrators can configure which entity types can use portals server-wide
- Configurable in `config.yml`
- Supports all Minecraft entity types (PLAYER, VILLAGER, ZOMBIE, etc.)

## Files Modified/Created

### New Files
1. **[`PortalAccessManager.java`](src/main/java/com/portal/plugin/PortalAccessManager.java)**
   - Manages all access control logic
   - Handles entity filtering
   - Provides API for permission checks

### Modified Files
1. **[`Portal.java`](src/main/java/com/portal/plugin/Portal.java)**
   - Added owner tracking (`ownerId`)
   - Added access mode (`accessMode`)
   - Added player lists (`allowedPlayers`, `deniedPlayers`)
   - Added `AccessMode` enum
   - Added access control methods
   - Updated `toJson()` to save access control data

2. **[`Main.java`](src/main/java/com/portal/plugin/Main.java)**
   - Initialized `PortalAccessManager`
   - Added `/portal access` command with subcommands
   - Added command help for access control

3. **[`config.yml`](src/main/resources/config.yml)**
   - Added `access_control` section
   - Added entity filtering configuration
   - Added default access mode setting
   - Added access control messages

## Commands

### Access Control Commands

```
/portal access <portal> mode <public|private|whitelist|blacklist>
```
Set the access mode for a portal. Only the owner or admins can use this.

```
/portal access <portal> allow <player>
```
Allow a specific player to use the portal.

```
/portal access <portal> deny <player>
```
Deny a specific player from using the portal.

```
/portal access <portal> remove <player>
```
Remove a player from both allowed and denied lists (reset their access).

```
/portal access <portal> list
```
View the portal's access settings, owner, and player lists.

```
/portal access <portal> owner <player>
```
Transfer ownership of the portal to another player. Only the current owner or admins can use this.

## Configuration

### config.yml Settings

```yaml
# Access Control Settings
access_control:
  # Entity filtering - control which entities can use portals
  entity_filtering:
    # Enable entity filtering
    enabled: true
    
    # List of allowed entity types (case-insensitive)
    allowed_entities:
      - PLAYER
      - VILLAGER
  
  # Default access mode for new portals
  # Options: PUBLIC, PRIVATE, WHITELIST, BLACKLIST
  default_access_mode: PUBLIC

# Messages
messages:
  access_denied: "§cYou don't have permission to use this portal!"
  not_owner: "§cYou are not the owner of this portal!"
  player_allowed: "§aPlayer %player% can now use this portal."
  player_denied: "§cPlayer %player% is now denied from using this portal."
  player_access_removed: "§ePlayer %player% access has been reset."
  access_mode_changed: "§aPortal access mode changed to %mode%."
```

## Usage Examples

### Example 1: Creating a Private Portal
```
1. /portal create MyPortal vertical
2. (Right-click blocks to add them)
3. /portal finish
4. /portal access MyPortal mode private
5. /portal access MyPortal allow FriendName
```

### Example 2: Blacklisting a Player
```
1. /portal access MyPortal mode blacklist
2. /portal access MyPortal deny GrieferName
```

### Example 3: Viewing Portal Access
```
/portal access MyPortal list
```
Output:
```
=== Portal Access: MyPortal ===
Owner: YourName
Access Mode: PRIVATE
Allowed Players:
  - Friend1
  - Friend2
```

### Example 4: Transferring Ownership
```
/portal access MyPortal owner NewOwner
```

## Permission Checks

The system checks permissions in the following order when a player tries to use a portal:

1. **Admin Override**: Players with `leverportal.admin` permission can always use any portal
2. **Entity Type Check**: Verify the entity type is allowed (if filtering is enabled)
3. **Owner Check**: Portal owners always have access
4. **Access Mode Check**: Based on the portal's access mode:
   - PUBLIC: Allow unless player is in denied list
   - PRIVATE: Allow only if player is in allowed list
   - WHITELIST: Allow only if player is in allowed list
   - BLACKLIST: Allow unless player is in denied list

## Integration Points

### For Developers

To integrate access control into your code:

```java
// Get the access manager
PortalAccessManager accessManager = plugin.getAccessManager();

// Check if a player can use a portal
if (accessManager.canPlayerUsePortal(player, portal)) {
    // Allow teleportation
} else {
    player.sendMessage("§cYou don't have permission to use this portal!");
}

// Check entity type
if (accessManager.isEntityTypeAllowed(EntityType.VILLAGER)) {
    // Allow villager to use portal
}

// Modify portal access
accessManager.setPortalAccessMode(portal, Portal.AccessMode.PRIVATE);
accessManager.allowPlayer(portal, playerUUID);
accessManager.denyPlayer(portal, playerUUID);
```

## Data Persistence

Access control data is saved in the portal JSON format:

```json
{
  "id": "MyPortal",
  "owner": "uuid-here",
  "accessMode": "PRIVATE",
  "allowedPlayers": ["uuid1", "uuid2"],
  "deniedPlayers": ["uuid3"],
  "orientation": "VERTICAL",
  "active": true,
  "blocks": [...]
}
```

## Permissions

- `leverportal.admin` - Bypass all access restrictions and manage any portal

## Future Enhancements

Potential improvements for future versions:

1. **Group-based Access**: Allow/deny entire permission groups
2. **Time-based Access**: Temporary access grants
3. **Access Logs**: Track who uses which portals
4. **Portal Sharing**: Co-ownership system
5. **Access Templates**: Predefined access configurations
6. **GUI Management**: In-game GUI for managing access

## Troubleshooting

### Players can't use their own portals
- Check if the portal has an owner set
- Verify the access mode is appropriate
- Use `/portal access <portal> list` to view settings

### Entity filtering not working
- Ensure `access_control.entity_filtering.enabled` is set to `true` in config.yml
- Check that the entity type is in the `allowed_entities` list
- Reload the plugin after config changes

### Access commands not working
- Verify the player is the portal owner or has admin permission
- Check that the portal name is correct (case-sensitive)
- Ensure the target player name is spelled correctly

## Support

For issues or questions about the access control system:
1. Check this documentation
2. Review the config.yml settings
3. Test with admin permissions to isolate permission issues
4. Check server logs for error messages

---

**Implementation Date**: 2026-04-03
**Version**: 1.0
**Author**: Portal Plugin Development Team
