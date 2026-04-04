# LeverPortal - Minecraft Plugin

A Minecraft plugin for version 1.21.11+ that allows players to create linked portals activated by levers.

## Features

- **Custom Portal Creation**: Build portals from any blocks in any shape
- **Vertical or Horizontal Orientation**: Choose how your portal is oriented
- **Lever Activation**: Use levers to activate/deactivate portals
- **Bidirectional Travel**: Pass through portals in both directions
- **Multiple Portal Pairs**: Create as many portal pairs as you need

## Installation

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Restart your server
4. The plugin will create a `LeverPortal` folder in the plugins directory

## Building from Source

### Requirements
- Java 17 or higher
- Spigot/Paper API 1.21.11+
- Maven or Gradle (optional)

### Compilation
```bash
# If using Maven
mvn clean package

# If using Gradle
gradle build

# Manual compilation
javac -cp spigot-1.21.11.jar src/main/java/com/portal/plugin/*.java
jar cvf LeverPortal.jar -C src/main/java/ . -C src/main/resources/ .
```

## Usage

### Creating a Portal

1. **Start Portal Creation**
   ```
   /portal create <name> <vertical|horizontal>
   ```
   Example: `/portal create portal1 vertical`

2. **Select Portal Blocks**
   - Right-click on blocks to add them to your portal
   - You can use any solid blocks
   - Add at least 2 blocks to form a portal
   - The blocks can be in any shape or pattern

3. **Finish Portal Creation**
   ```
   /portal finish
   ```
   Or cancel with: `/portal cancel`

4. **Repeat for Second Portal**
   - Create another portal with a different name
   - Example: `/portal create portal2 vertical`

### Linking Portals

Once you have two portals created, link them together:
```
/portal link <portal1> <portal2>
```
Example: `/portal link portal1 portal2`

**Requirements for linking:**
- Both portals must have the same orientation (both vertical or both horizontal)
- Portals must be at least 3 blocks apart
- Portals must be in the same world

### Activating Portals

1. **Place a Lever**
   - Place a lever within 10 blocks of your portal
   - Right-click the lever to associate it with the portal

2. **Toggle the Portal**
   - Flip the lever ON to activate both connected portals
   - Flip the lever OFF to deactivate them

3. **Travel Through**
   - Walk into an active portal to teleport to the linked portal
   - Works in both directions!

### Managing Portals

**List all portals:**
```
/portal list
```

**Remove a portal:**
```
/portal remove <name>
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/portal` | Show help menu | `leverportal.use` |
| `/portal create <name> <orientation>` | Start creating a portal | `leverportal.create` |
| `/portal finish` | Complete portal creation | `leverportal.create` |
| `/portal cancel` | Cancel portal creation | `leverportal.create` |
| `/portal link <portal1> <portal2>` | Link two portals | `leverportal.link` |
| `/portal remove <name>` | Remove a portal | `leverportal.remove` |
| `/portal list` | List all portals | `leverportal.use` |

## Permissions

- `leverportal.use` - Basic portal usage (default: op)
- `leverportal.create` - Create portals (default: op)
- `leverportal.link` - Link portals (default: op)
- `leverportal.remove` - Remove portals (default: op)

## Examples

### Example 1: Simple Vertical Portal
```
/portal create home vertical
[Right-click 4 blocks in a vertical line]
/portal finish

/portal create base vertical
[Right-click 4 blocks in a vertical line]
/portal finish

/portal link home base
[Place lever near home portal and flip it]
```

### Example 2: Horizontal Portal Frame
```
/portal create nether_gate horizontal
[Right-click blocks forming a horizontal frame]
/portal finish

/portal create overworld_gate horizontal
[Right-click blocks forming a horizontal frame]
/portal finish

/portal link nether_gate overworld_gate
[Place lever and activate]
```

### Example 3: Custom Shape Portal
```
/portal create custom1 vertical
[Right-click blocks in any pattern - circle, square, etc.]
/portal finish

/portal create custom2 vertical
[Right-click blocks in any pattern]
/portal finish

/portal link custom1 custom2
```

## Technical Details

### Portal Mechanics
- Portals can be made from any solid blocks
- Portal shape is completely customizable
- Minimum 2 blocks required per portal
- Teleportation has a 2-second cooldown to prevent rapid re-teleportation
- Player's view direction (yaw/pitch) is preserved during teleportation

### Data Storage
- Portal data is saved to `plugins/LeverPortal/portals/portals.json`
- Data is automatically saved on server shutdown
- Data is loaded on server startup

### Orientation Types
- **Vertical**: Portal stands upright (like a doorway)
- **Horizontal**: Portal lies flat (like a floor/ceiling portal)

## Troubleshooting

**Portal won't activate:**
- Make sure both portals are linked
- Check that the lever is within 10 blocks of the portal
- Verify the lever is properly associated (message should appear when placing)

**Can't link portals:**
- Ensure both portals have the same orientation
- Check that portals are at least 3 blocks apart
- Verify both portals are in the same world

**Teleportation not working:**
- Make sure both portals are activated (lever is ON)
- Check that you're walking through the portal blocks
- Wait for the 2-second cooldown between teleports

## Version Compatibility

- **Minecraft Version**: 1.21.11+
- **Server Software**: Spigot, Paper, or compatible forks
- **Java Version**: 17 or higher

## License

This plugin is provided as-is for educational and entertainment purposes.

## Support

For issues, questions, or suggestions, please create an issue on the project repository.
