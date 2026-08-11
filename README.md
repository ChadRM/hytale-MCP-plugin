# Hytale MCP

**Model Context Protocol plugin for Hytale servers**

Connect AI assistants like OpenCode, Claude, ChatGPT, and Gemini directly to your Hytale server

![Preview](./doc/preview.jpg)

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.2.0-green.svg)](https://github.com/Metrakit/hytale-MCP-plugin/releases)
[![Java](https://img.shields.io/badge/java-25-orange.svg)](https://www.oracle.com/java/)
[![MCP](https://img.shields.io/badge/MCP-Protocol-purple.svg)](https://modelcontextprotocol.io)

Guide: https://top-games.net/guides/connect-ai-hytale-server-mcp

[Features](#features) • [Installation](#installation) • [Configuration](#configuration) • [Usage](#usage) • [API](#api-reference) • [Contributing](#contributing)

---

## Table of Contents

- [About](#about)
- [Features](#features)
- [This Fork](#this-fork)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [API Reference](#api-reference)
- [Extending](#extending-with-custom-features)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Building](#building-from-source)
- [Contributing](#contributing)
- [License](#license)
- [Support](#support)

## About

Hytale MCP brings the power of AI assistants to your Hytale server through the Model Context Protocol (MCP). This plugin enables AI models like Claude, ChatGPT, and Gemini to interact with your server, allowing for automation, creative building, and enhanced server management.

### Use Cases

- **Creative Building** - Tell AI to "build a Eiffel Tower at my location" and watch it construct complex structures
- **Server Automation** - Automate routine tasks like player management
- **Administrative Tools** - Manage your server with natural language commands
- **Development & Testing** - Rapidly prototype and test game mechanics

Whether you're a server administrator, builder, or developer, Hytale MCP provides a secure and extensible foundation for AI integration.

## Features

### Core Capabilities

- **Full MCP Protocol Support** - Standards-compliant Model Context Protocol implementation
- **Secure Authentication** - Token-based auth with separate admin and player permissions
- **Extensible Architecture** - Easy-to-use plugin system for adding custom features
- **Granular Permissions** - Fine-grained access control for different user levels
- **AI Client Compatible** - Works with OpenCode, Claude, ChatGPT, Gemini, and any MCP-compatible client

### Built-in Tools

**39 tools** across eight areas:

| Area | Tools |
|---|---|
| **Block read/write** | `set_block` `get_block` `break_block` `set_blocks_batch` `break_blocks_batch` |
| **Region operations** | `scan_region` `replace_blocks_in_region` `get_heightmap` `verify_placement` `flatten_terrain` |
| **Shape generators** | `generate_sphere` `generate_cylinder` `generate_staircase` `generate_lattice_column` `generate_road_corridor` |
| **NPCs** | `spawn_npc` `despawn_npc` `get_npc_position` `set_npc_path` `set_npc_flag` `start_npc_trace` `stop_npc_trace` `list_npc_roles` `list_models` |
| **Map waypoints** | `add_waypoint` `list_waypoints` `remove_waypoint` |
| **Players** | `list_players` `get_player_position` `send_chat_message` `broadcast_message` `give_item` |
| **Discovery** | `list_blocks` `list_items` `get_building_guide` |
| **Server** | `get_server_info` `get_world_info` `get_logs` `execute_command` |

Key capabilities:

- **Read before write** - Every mutation has a read counterpart, so an agent can inspect world state instead of guessing. Fluids are reported separately from block type, since a water-filled position otherwise reads as plain air.
- **Bulk operations** - Batch placement, batch breaking, region scanning, and region-wide find-and-replace, each in a single call instead of thousands of round trips.
- **Plan-then-place geometry** - Shape generators compute a block plan and return it *without touching the world*, so the plan can be inspected, edited, or rejected before being fed to `set_blocks_batch`.
- **Verification** - `verify_placement` checks a whole expected build against live world state in one call, optionally flagging blocks left floating with no support.
- **NPC control** - Spawn, despawn, locate, assign patrol paths, toggle role flags, and record fine-grained behavioral traces to disk.
- **Configurable safety limits** - Batch size and scan/replace volumes are capped by config, with per-tool permissions split between admin and player tokens.

### This Fork

This is a fork of [Metrakit/hytale-MCP-plugin](https://github.com/Metrakit/hytale-MCP-plugin), which established the MCP server, auth model, feature-registry architecture, and the original 14 tools.

This fork adds **25 tools** and fixes several bugs in the originals:

- **World reading** - `get_block`, `scan_region`, `get_heightmap`. The upstream plugin could write blocks but never read them back, so an agent had no way to see what it had actually built, or what terrain it was building on.
- **Bulk editing** - `break_block`, `break_blocks_batch`, `replace_blocks_in_region`
- **Shape generators** - sphere, cylinder, staircase, lattice column, road corridor, all pure-geometry
- **Verification** - `verify_placement`
- **NPC subsystem** - spawn, despawn, position, pathing, role flags, tracing, plus `list_npc_roles` and `list_models`
- **Map waypoints** - `add_waypoint`, `list_waypoints`, `remove_waypoint`
- **Item discovery** - `list_items`. Items are a separate asset registry from Blocks, and `list_blocks` never surfaced them.

Fixes to upstream tools: `send_chat_message` never actually delivered a message; `set_block` could not overwrite an existing block in place, and gained rotation support; `list_players` and `get_player_position` could hang the server.

## Requirements

- [Nitrado WebServer plugin](https://github.com/nitrado/hytale-plugin-webserver)

## Installation

### Quick Start

1. **Download** the latest `MCP-1.*.*.jar` from the [releases page](https://github.com/Metrakit/hytale-MCP-plugin/releases)
2. **Install** the [Nitrado WebServer plugin](https://github.com/nitrado/hytale-plugin-webserver) (required dependency)
3. **Place** both JAR files in your server's `mods/` directory
4. **Start** your server to generate the default configuration
5. **Configure** your tokens and permissions (see [Configuration](#configuration))
6. **Restart** your server

The plugin will be available at `http://your-server:port/Top-Games/MCP/mcp`

### Quick Example

Once installed, you can test the connection:

```bash
# Test basic connectivity
curl http://localhost:port/Top-Games/MCP/mcp

# List available tools (with authentication)
curl -X POST http://localhost:port/Top-Games/MCP/mcp \
  -H "Authorization: Bearer your-admin-token" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/list",
    "id": 1
  }'
```

## Configuration

After the first run, a configuration file will be created at `mods/MCP/config.json`:

> **Note**: The server port is configured in the WebServer plugin settings, not here.

```json
{
  "auth": {
    "enabled": true,
    "adminTokens": [
      "your-admin-token-here"
    ],
    "playerTokens": [
      "your-player-token-here"
    ]
  },
  "features": {
    "players": {
      "listPlayers": false,
      "executeCommand": false,
      "broadcastMessage": false,
      "setBlock": false,
      "getPlayerPosition": false,
      "getLogs": false,
      "sendChatMessage": false,
      "getBlockTypes": false,
      "listBlocks": false,
      "getWorldInfo": false,
      "getServerInfo": false
    },
    "admins": {
      "listPlayers": true,
      "executeCommand": true,
      "broadcastMessage": true,
      "setBlock": true,
      "getPlayerPosition": true,
      "getLogs": true,
      "sendChatMessage": true,
      "getBlockTypes": true,
      "listBlocks": true,
      "getWorldInfo": true,
      "getServerInfo": true
    },
    "maxBlocksBatch": 1000
  }
}
```

### Configuration Reference

#### Server Settings

#### Authentication Settings

| Option | Type | Description |
|--------|------|-------------|
| `auth.enabled` | boolean | Enable/disable token authentication |
| `auth.adminTokens` | string[] | Tokens with full administrative access |
| `auth.playerTokens` | string[] | Tokens with limited player-level access |

#### Feature Permissions

Configure feature availability for each permission level:

| Permission | Description | Tools Using This Permission |
|---------|-------------|---------------------------|
| `listPlayers` | List all connected players | `list_players` |
| `getServerInfo` | Get server information and status | `get_server_info` |
| `executeCommand` | Execute server commands | `execute_command`, `give_item` |
| `broadcastMessage` | Send messages to all players | `broadcast_message` |
| `getLogs` | Retrieve and filter server logs | `get_logs` |
| `setBlock` | Place blocks at coordinates | `set_block`, `set_blocks_batch`, `flatten_terrain` |
| `getBlockTypes` | Get list of available block types | `get_block_types`, `get_building_guide` |
| `listBlocks` | Search and filter blocks with categorization | `list_blocks` |
| `getPlayerPosition` | Get player position, rotation, and world | `get_player_position` |
| `getWorldInfo` | Get world information and properties | `get_world_info` |
| `sendChatMessage` | Send chat message to specific player | `send_chat_message` |

**Additional Settings:**
- **`maxBlocksBatch`** (int, default: 1000) - Maximum blocks per `set_blocks_batch` call

**Permission Structure:**
- **`features.admins`** - Features available to admin token holders
- **`features.players`** - Features available to player token holders

### Disabling HTTPS (HTTP Connection)

By default, the Nitrado WebServer plugin uses HTTPS. If you need to connect via HTTP, you can disable TLS in the WebServer plugin configuration.

**Location:** `mods/Nitrado_WebServer/config.json`

Add or modify the `Tls` section:

```json
{
  "Tls": {
    "Insecure": true
  }
}
```

## Usage

### Connecting AI Clients

To connect an AI assistant to your Hytale server:

1. **Configure your AI client** to connect to your MCP endpoint
2. **Provide the endpoint URL**: `http://your-server:port/Top-Games/MCP/mcp`
3. **Authenticate** using a Bearer token in the request header:
   ```
   Authorization: Bearer your-token-here
   ```

### Example Client Setup

For OpenCode or other MCP clients, add this configuration:

```json
{
  "mcpServers": {
    "hytale-mcp": {
      "url": "http://your-server:port/Top-Games/MCP/mcp",
      "headers": {
        "Authorization": "Bearer your-admin-token"
      }
    }
  }
}
```

## API Reference

### Available Tools

#### `set_block`

Places a single block at specified coordinates.

**Example Prompt:**
> "Place a sandstone brick at coordinates x:10, y:64, z:10"

**Parameters:**
- `x` (int): X coordinate
- `y` (int): Y coordinate
- `z` (int): Z coordinate
- `blockType` (string): Block identifier (e.g., `Rock_Sandstone_Brick`)
- `world` (string): World name

**Example Response:**
```json
{
  "success": true,
  "message": "Block placed successfully",
  "x": 10,
  "y": 64,
  "z": 10,
  "world": "world",
  "blockType": "Rock_Sandstone_Brick"
}
```

#### `list_players`
Lists all currently connected players on the server.

**Example Prompt:**
> "Who is currently online on the server?"
> "List all connected players"

**Response:**
```json
{
  "count": 5,
  "players": [
    {
      "uuid": "player-uuid",
      "name": "PlayerName"
    }
  ]
}
```

#### `get_server_info`
Gets information about the server including name, version, and uptime.

**Example Prompt:**
> "What's the server status?"
> "Show me server information and uptime"

**Response:**
```json
{
  "name": "My Hytale Server",
  "version": "1.0.0",
  "uptime": "2 days, 5 hours, 30 minutes",
  "tps": 20.0
}
```

#### `list_blocks`
Lists all available blocks with smart categorization and caching. Perfect for discovering item IDs for building or giving items.

**Example Prompt:**
> "Show me all stone blocks"
> "Find building blocks that contain 'brick'"
> "List 20 decoration blocks"

**Parameters:**
- `limit` (int, optional): Maximum number of blocks to return
- `search` (string, optional): Search term to filter blocks by name (case-insensitive)
- `category` (string, optional): Filter by category (building, decoration, nature, ore, stone, wood, metal, glass, food, tool, weapon, misc)

**Example Request - Search for stones:**
```json
{
  "search": "stone",
  "limit": 10
}
```

**Example Request - Get all building blocks:**
```json
{
  "category": "building",
  "limit": 50
}
```

**Response:**
```json
{
  "total": 1234,
  "returned": 10,
  "blocks": [
    {
      "name": "hytale:stone_brick",
      "id": 42,
      "category": "building"
    },
    {
      "name": "hytale:sandstone",
      "id": 87,
      "category": "stone"
    }
  ],
  "categoryStats": {
    "building": 250,
    "stone": 180,
    "wood": 120,
    "nature": 300,
    "decoration": 95,
    "misc": 289
  },
  "searchTerm": "stone"
}
```

#### `give_item`
Gives an item to a player using the `/give` command.

**Example Prompt:**
> "Give Michel 10 sticks"
> "Give 64 stone bricks to PlayerName"

**Parameters:**
- `player` (string): Player name to give the item to
- `itemId` (string): Item ID (use `list_blocks` to find IDs)
- `quantity` (int, optional): Quantity to give (default: 1)

**Example Request:**
```json
{
  "player": "Michel",
  "itemId": "Ingredient_Stick",
  "quantity": 10
}
```

**Response:**
```json
{
  "player": "Michel",
  "itemId": "Ingredient_Stick",
  "quantity": 10,
  "command": "give Michel Ingredient_Stick --quantity=10",
  "status": "executed"
}
```

#### `flatten_terrain`
Flattens a rectangular terrain area at a specific height, perfect for building foundations. Fills below with blocks and clears above with air.

**Example Prompt:**
> "Flatten a 50x50 area at my location for building a castle"
> "Create a stone platform 100 blocks wide at height 64"
> "Prepare the terrain for a large building, make it flat"

**Parameters:**
- `world` (string): World UUID
- `x1`, `z1` (int): First corner coordinates
- `x2`, `z2` (int): Second corner coordinates
- `y` (int): Height level to flatten at
- `fillBlock` (string, optional): Block type to fill below surface (default: "hytale:dirt")
- `maxHeight` (int, optional): Maximum height to clear above (default: y+10)

**Example Request - Create a 50x50 stone platform:**
```json
{
  "world": "world-uuid-here",
  "x1": 100,
  "z1": 100,
  "x2": 150,
  "z2": 150,
  "y": 64,
  "fillBlock": "hytale:stone"
}
```

**Response:**
```json
{
  "area": 2601,
  "minX": 100,
  "maxX": 150,
  "minZ": 100,
  "maxZ": 150,
  "flattenY": 64,
  "maxHeight": 74,
  "fillBlock": "hytale:stone",
  "blocksPlaced": 166464,
  "blocksCleared": 26010,
  "totalBlocks": 192474,
  "durationMs": 1250,
  "status": "success"
}
```

#### `execute_command`
Executes a server command.

**Example Prompt:**
> "Make Michel an operator"
> "Execute the command 'weather clear'"
> "Set the time to day"

**Parameters:**
```json
{
  "command": "op Michel"
}
```

**Response:**
```json
{
  "command": "op Michel",
  "status": "executed"
}
```

#### `broadcast_message`
Broadcasts a message to all connected players.

**Example Prompt:**
> "Announce to everyone that the server will restart in 5 minutes"
> "Broadcast a welcome message to all players"

**Parameters:**
```json
{
  "message": "Welcome to our server!"
}
```

**Response:**
```json
{
  "message": "Welcome to our server!",
  "status": "broadcasted"
}
```

#### `get_logs`
Retrieves server logs with optional filtering.

**Example Prompt:**
> "Show me the last 50 error logs"
> "Get server logs from yesterday"
> "What are the recent warnings in the logs?"

**Parameters:**
- `lines` (int, optional): Number of lines to retrieve (default: 100, max: 1000)
- `level` (string, optional): Filter by log level (e.g., "INFO", "WARNING", "ERROR", "SEVERE")
- `date` (string, optional): Log file date in format "YYYY-MM-DD"

**Example Request:**
```json
{
  "lines": 50,
  "level": "ERROR"
}
```

**Response:**
```json
{
  "lineCount": 50,
  "level": "ERROR",
  "content": "[2026-02-01 10:30:45] [ERROR] Failed to connect to database\n[2026-02-01 10:30:45 [ERROR] Connection timeout\n...",
  "timestamp": "2026-02-01T10:32:00"
}
```

**Example Request for specific date:**
```json
{
  "date": "2024-01-14",
  "lines": 200
}
```

#### `set_blocks_batch`
Sets multiple blocks at specified world coordinates in a single request (configurable limit, default: 1000 blocks).

**Example Prompt:**
> "Build a 10x10 stone wall at my location"
> "Create a house at coordinates x:100, y:64, z:200"
> "Build an Eiffel Tower replica at my position"
> "Construct a small castle near me"

**Parameters:**
- `blocks` (array): Array of block objects, each with x, y, z, blockType
  - `x` (int): X coordinate
  - `y` (int): Y coordinate
  - `z` (int): Z coordinate
  - `blockType` (string): Block identifier (e.g., `Rock_Sandstone_Brick`)
- `world` (string): World name (optional, defaults to current world)

**Request Example:**
```json
{
  "blocks": [
    {"x": 10, "y": 64, "z": 10, "blockType": "Rock_Sandstone_Brick"},
    {"x": 11, "y": 64, "z": 10, "blockType": "Rock_Sandstone_Brick"},
    {"x": 10, "y": 64, "z": 11, "blockType": "Rock_Sandstone_Brick"}
  ]
}
```

**Response:**
```json
{
  "total": 3,
  "success": 3,
  "failed": 0,
  "results": [
    {"x": 10, "y": 64, "z": 10, "blockType": "Rock_Sandstone_Brick", "status": "success"},
    {"x": 11, "y": 64, "z": 10, "blockType": "Rock_Sandstone_Brick", "status": "success"},
    {"x": 10, "y": 64, "z": 11, "blockType": "Rock_Sandstone_Brick", "status": "success"}
  ]
}
```

#### `get_player_position`
Gets the current position (x, y, z) and rotation (yaw, pitch) of a specific player.

**Example Prompt:**
> "Where is Michel located?"
> "Get my current position"
> "What are the coordinates of PlayerName?"

**Parameters:**
- `player` (string): Player name

**Request:**
```json
{
  "player": "Michel"
}
```

**Response:**
```json
{
  "name": "Michel",
  "uuid": "xxxxx-xxxxx-xxxxx-xxxxx-xxxxx",
  "position": {
    "x": 1943.18,
    "y": 124.0,
    "z": 603.64,
    "yaw": -1.91,
    "pitch": 0.0,
    "worldUuid": "xxxxx-xxxxx-xxxxx-xxxxx-xxxxx"
  }
}
```

#### `get_world_info`
Gets information about a world including name, seed, and dimension.

**Example Prompt:**
> "What's the world seed?"
> "Show me information about the current world"
> "Where is the spawn point?"

**Response:**
```json
{
  "name": "My World",
  "seed": 123456789,
  "dimension": "overworld",
  "spawn": {
    "x": 0,
    "y": 100,
    "z": 0
  }
}
```

#### `send_chat_message`
Sends a chat message to a specific player.

**Example Prompt:**
> "Send Michel a welcome message"
> "Tell PlayerName that their building looks great"
> "Message the admin about the issue"

**Parameters:**
- `player` (string): Target player name
- `message` (string): Message to send

**Request:**
```json
{
  "player": "Michel",
  "message": "Welcome to the server!"
}
```

**Response:**
```json
{
  "message": "Welcome to the server!",
  "status": "sent"
}
```

---

### Block Read/Write

#### `get_block`

Gets the block type actually placed at world coordinates - the read counterpart to `set_block`. Also reports fluid presence, type, and level, which is tracked separately from block type and would otherwise read as plain air.

**Example Prompt:**
> "What block is at x:10, y:64, z:10?"

**Parameters:**
- `x`, `y`, `z` (int, required): Coordinates
- `world` (string, required): World UUID

#### `break_block`

Clears the block at world coordinates back to air, the same as a player breaking it (drops items, plays break effects).

**Parameters:**
- `x`, `y`, `z` (int, required): Coordinates
- `world` (string, required): World UUID

#### `break_blocks_batch`

Clears up to `maxBlocksBatch` blocks (default 1000) back to air in one call - the batch counterpart to `break_block`.

**Example Prompt:**
> "Clear out the whole room I just scanned"

**Parameters:**
- `world` (string, required): World UUID
- `coords` (array, required): List of `{x, y, z}` objects to clear

---

### Region Operations

#### `scan_region`

Scans a bounding box (any two opposite corners) and reports every non-air block, every position holding fluid, and counts of air and unloaded positions. Max volume `maxScanVolume` (default 32,768).

Fluid is reported separately because a position can be fluid-filled while its block type reads as air.

**Example Prompt:**
> "Scan the area around my base and tell me what's there"

**Parameters:**
- `x1`, `y1`, `z1` (int, required): First corner
- `x2`, `y2`, `z2` (int, required): Opposite corner
- `world` (string, required): World UUID

#### `replace_blocks_in_region`

Scans a bounding box for every block matching `matchBlockType` and replaces it - a server-side find-and-replace, instead of reading a region and writing back thousands of individual blocks. Max volume `maxReplaceVolume` (default 500,000).

Omit `replaceBlockType` for a read-only dry run that reports what *would* change.

**Example Prompt:**
> "Replace all the dirt in this region with stone"
> "How much sand is in this area?" (dry run)

**Parameters:**
- `x1`, `y1`, `z1` (int, required): First corner
- `x2`, `y2`, `z2` (int, required): Opposite corner
- `world` (string, required): World UUID
- `matchBlockType` (string, required): Block type to search for
- `replaceBlockType` (string, optional): Replacement. Omit for a dry run.

#### `get_heightmap`

Gets the ground surface height and top block type for every column in an X/Z area, plus the water surface above it if any. A compact terrain-shape query for road routing, river finding, and build siting - much lighter than `scan_region` over the same footprint. Max `maxHeightmapSamples` (default 10,000).

**Example Prompt:**
> "What does the terrain look like between my base and the river?"

**Parameters:**
- `x1`, `z1` (int, required): First corner
- `x2`, `z2` (int, required): Opposite corner
- `world` (string, required): World UUID
- `stride` (int, optional): Sample every Nth block per axis. Default 1; use a larger stride for a coarse scan of a large area.
- `skipFoliage` (boolean, optional): Walk down past overhanging tree canopy to report actual ground instead of the literal topmost block. Default true.

#### `verify_placement`

Verifies a list of expected block placements against live world state in one call, replacing one `get_block` per position.

**Example Prompt:**
> "Did that whole structure actually place correctly?"

**Parameters:**
- `world` (string, required): World UUID
- `blocks` (array, required): List of `{x, y, z, blockType}` expected placements
- `checkSupport` (boolean, optional): Also flag any entry whose position below is air or unloaded - i.e. floating with nothing underneath. Default false.

---

### Shape Generators

All generators are **pure geometry**. They compute a block plan and return it *without modifying the world*, so the plan can be inspected or edited before being passed to `set_blocks_batch`.

#### `generate_sphere`

Computes a sphere or hollow spherical shell around a center point.

**Parameters:**
- `x`, `y`, `z` (int): Center coordinates
- `radius` (int, required): Radius in blocks
- `blockType` (string, required): Block type to fill with
- `hollow` (boolean, optional): Shell instead of solid ball. Default false.
- `shellThickness` (int, optional): Shell thickness when hollow. Default 1.

#### `generate_cylinder`

Computes a vertical cylinder or hollow cylindrical shell - towers, pillars, silos.

**Parameters:**
- `x`, `y`, `z` (int): Base center; the cylinder rises from here
- `radius`, `height` (int, required): Dimensions in blocks
- `blockType` (string, required): Block type to fill with
- `hollow` (boolean, optional): Wall only. Default false.
- `shellThickness` (int, optional): Wall thickness when hollow. Default 1.
- `capBottom`, `capTop` (boolean, optional): Fill the end discs solid even when hollow. Default false.

#### `generate_staircase`

Computes a staircase ascending from a base landing in one cardinal direction.

**Parameters:**
- `x`, `y`, `z` (int): Base landing coordinates
- `direction` (string, required): `North`, `South`, `East`, or `West`
- `steps`, `width` (int, required): Step count, and width perpendicular to travel
- `blockType` (string, required): Block type
- `stepDepth` (int, optional): Horizontal run per step. Default 1.
- `stepHeight` (int, optional): Vertical rise per step. Default 1.
- `hollow` (boolean, optional): Place only each step's tread instead of backfilling solid to the landing. Default false, which avoids floating overhangs.

#### `generate_lattice_column`

Computes a tall thin lattice tower - vertical corner posts evenly spaced around a circle, optionally connected by horizontal ring braces.

**Parameters:**
- `x`, `y`, `z` (int): Base center
- `height`, `radius` (int, required): Tower height, and distance from center axis to each post
- `postCount` (int, required): Number of corner posts (e.g. 4 for a square tower)
- `postBlockType` (string, required): Block type for the posts
- `braceBlockType` (string, optional): Block type for ring braces. Omit for posts only.
- `braceInterval` (int, optional): Height between braces. Required if `braceBlockType` is given; a ring is always placed at the base.

#### `generate_road_corridor`

Computes a road or path from a chain of waypoints, with consecutive pairs forming straight segments. Enforces a maximum grade of 1 block of rise per 2 of horizontal travel.

**Parameters:**
- `waypoints` (array, required): Ordered list of 2+ `{x, y, z}` points
- `width` (int, required): Total path width (e.g. 3 for a cardinal road, 4-5 for a diagonal)
- `blockType` (string, required): Path surface block type
- `shoulderWidth` (int, optional): Width of a same-elevation shoulder each side. Omit or 0 for none.
- `shoulderBlockType` (string, optional): Required if `shoulderWidth` > 0.

---

### NPCs

#### `spawn_npc`

Spawns an NPC by role name. Either give explicit coordinates, or a player to spawn near.

**Example Prompt:**
> "Spawn a guard in front of me"

**Parameters:**
- `world` (string, required): World UUID
- `role` (string, required): Role name - see `list_npc_roles`
- `player` (string, optional): Player to spawn near. Required if `x`/`y`/`z` omitted.
- `offsetForward` (number, optional): Blocks in front of the player along their facing yaw. Default 3.
- `x`, `y`, `z` (number, optional): Explicit coordinates. Required together if `player` omitted.

#### `despawn_npc`

Removes NPC entities near a position.

**Parameters:**
- `world` (string, required): World UUID
- `player` or `x`/`y`/`z`: Search origin
- `radius` (number, optional): Search radius. Default 10.
- `all` (boolean, optional): Remove every NPC in radius rather than just the nearest. Default false.
- `npcTypeId` (string, optional): Only remove NPCs whose role id matches exactly (case-insensitive) - use to avoid sweeping up unrelated wildlife.

#### `get_npc_position`

Gets live position, rotation, and role of the NPC nearest a search position.

**Parameters:** same search arguments as `despawn_npc` (`world`, `player` or `x`/`y`/`z`, `radius`, `npcTypeId`)

#### `set_npc_path`

Assigns a patrol path to the NPC nearest a search position.

**Parameters:**
- `world` (string, required): World UUID
- `waypoints` (array, required): Ordered list of 2+ absolute `{x, y, z}` points the NPC will walk
- `player` or `x`/`y`/`z`: Search origin for the target NPC
- `radius` (number, optional): Search radius. Default 10.

#### `set_npc_flag`

Sets a Role flag slot on the nearest matching NPC, by integer index. Flag *names* only exist at Role-build time and are not available at runtime, so slots are addressed positionally.

**Parameters:**
- `world` (string, required): World UUID
- `flagIndex` (int, required): 0-based flag slot
- `value` (boolean, optional): Default true.
- `player` or `x`/`y`/`z`, `radius`, `npcTypeId`: Target search

#### `start_npc_trace` / `stop_npc_trace`

Starts a fine-grained trace of the NPC nearest a search position, sampling to a file on disk; `stop_npc_trace` ends it and closes the file. Useful for debugging pathing and behavior over time.

**Parameters (`start_npc_trace`):**
- `world` (string, required): World UUID
- `player` or `x`/`y`/`z`, `radius`, `npcTypeId`: Target search. The radius is re-used each sample to re-find the NPC.
- `intervalMs` (int, optional): Sampling interval. Default 200.

`stop_npc_trace` takes no parameters.

#### `list_npc_roles`

Lists registered NPC role names valid for `spawn_npc`.

**Parameters:**
- `includeNonSpawnable` (boolean, optional): Also include abstract/template roles. Default false.

#### `list_models`

Lists registered Model asset ids usable as an NPC Role's Appearance value.

**Parameters:**
- `search` (string, optional): Case-insensitive substring filter

---

### Map Waypoints

#### `add_waypoint`

Places a waypoint marker on a player's in-game world map at the given X/Z. Personal by default; `shared=true` makes it visible to everyone. Returns the marker id needed by `remove_waypoint`.

Placement is bounded by the player's view radius - the marker must land within their currently visible range, not anywhere on the map.

**Parameters:**
- `player` (string, required): Player name or UUID
- `x`, `z` (int, required): World coordinates
- `name` (string, required): Marker label, max 24 characters
- `world` (string, required): World UUID
- `icon` (string, optional): Marker icon filename. Defaults to `UserA.png`, the icon the in-game Quick Marker button uses - deliberately *not* the engine's own default `User1.png`, which is a missing asset that silently fails to render.
- `colorHex` (string, optional): Tint, e.g. `#ffcc00`. Default none.
- `shared` (boolean, optional): Visible to all players. Default false.

#### `list_waypoints`

Lists the markers currently on a player's map - their personal markers plus any shared markers in that world.

**Parameters:**
- `player` (string, required): Player name or UUID
- `world` (string, required): World UUID

#### `remove_waypoint`

Removes a marker by id, personal or shared.

**Parameters:**
- `player` (string, required): Player name or UUID
- `markerId` (string, required): From `add_waypoint`'s response or `list_waypoints`
- `world` (string, required): World UUID

---

### Discovery

#### `list_items`

Lists real Item asset ids - weapons, tools, armor, food. Items live in a separate registry from Blocks, so `list_blocks` does not surface them.

**Parameters:**
- `search` (string, optional): Case-insensitive substring filter
- `category` (string, optional): Exact category, e.g. `Weapon.Sword`
- `limit` (int, optional): Maximum results

#### `get_building_guide`

Returns the complete Hytale construction guide for AI agents - block names, coordinate rules, support and gravity constraints, and blueprints. Call this before `set_blocks_batch`; it is what keeps a generated structure from collapsing or floating.

Takes no parameters.

### MCP Protocol Endpoints

The plugin implements standard MCP JSON-RPC 2.0 endpoints:

#### POST `/mcp`

Main endpoint for MCP tool operations.

**Available Methods:**
- `initialize` - Initialize MCP connection and negotiate capabilities
- `tools/list` - List available tools based on authentication level
- `tools/call` - Execute a tool with specified parameters
- `ping` - Health check endpoint

**Example Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "params": {},
  "id": 1
}
```

#### GET `/mcp`

Returns plugin metadata and version information.

**Example Response:**
```json
{
  "name": "MCP",
  "version": "1.0.0",
  "protocol": "mcp",
  "description": "Model Context Protocol for Hytale servers"
}
```

## Extending with Custom Features

Creating a custom feature is simple (with another plugin by example). Implement the `McpFeature` interface:

```java
public class MyCustomFeature implements McpFeature {
    private final HytaleLogger logger;

    public MyCustomFeature(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "my_custom_feature";
    }

    @Override
    public McpTool getToolDefinition() {
        return new McpTool(
            "my_custom_feature",
            "Description of what this feature does",
            "function"
        );
    }

    @Override
    public McpToolResponse execute(McpToolCall call, McpAuthManager.AuthLevel authLevel) {
        try {
            // Your custom logic here
            
            JsonObject result = new JsonObject();
            result.addProperty("data", "your result");
            
            return McpToolResponse.success(GSON.toJson(result));
        } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error in custom feature");
            return McpToolResponse.error("Failed: " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(McpAuthManager.AuthLevel authLevel, McpConfig config) {
        // Define who can use this feature
        return authLevel == McpAuthManager.AuthLevel.ADMIN;
    }
}
```

Then register it in your plugin's `registerFeatures()` method:

```java
featureRegistry.registerFeature(new MyCustomFeature(logger));
```

## Best Practices

- **Strong Tokens** - Generate cryptographically secure random tokens (32+ characters)
  ```bash
  # Example token generation
  openssl rand -base64 32
  ```

- **Minimal Permissions** - Only enable features that users actually need
  - Restrict `executeCommand` to admin tokens only
  - Disable player features if not required

## Troubleshooting

### Common Issues

<details>
<summary><b>Plugin not loading</b></summary>

**Symptoms:** MCP plugin doesn't appear in server logs or plugin list

**Solutions:**
- Verify the WebServer plugin is installed and enabled
- Check that the JAR file is in the correct `mods/` directory
- Review server startup logs for error messages
</details>

<details>
<summary><b>Authentication failures</b></summary>

**Symptoms:** 401 Unauthorized or authentication errors

**Solutions:**
- Confirm token exactly matches configuration
- Verify token is in the correct array (`adminTokens` vs `playerTokens`)
- Check that `auth.enabled` is set to `true`
- Ensure you're using the correct header: `Authorization: Bearer <token>`
- Try with authentication disabled temporarily to isolate the issue

</details>

<details>
<summary><b>Features not available</b></summary>

**Symptoms:** Tools not showing up in `tools/list` response

**Solutions:**
- Check feature permissions in `config.json` for your auth level
- Verify the feature is enabled for your token type (admin/player)
- Review server logs for feature initialization errors
- Ensure configuration file is valid JSON
- Restart server after configuration changes

</details>

<details>
<summary><b>Connection refused</b></summary>

**Symptoms:** Cannot connect to MCP endpoint

**Solutions:**
- Verify WebServer plugin is running and configured correctly
- Check the correct port is being used
- Ensure firewall allows connections to the port
- Confirm the endpoint path is correct: `/Top-Games/MCP/mcp`
- Test with `curl` or similar tool (Postman) to verify basic connectivity

</details>

## FAQ

### General Questions

**Q: What is the Model Context Protocol (MCP)?**
A: MCP is an open standard that enables AI assistants to securely connect to external tools and data sources. It allows AI models to interact with your Hytale server in a standardized way.

**Q: Which AI assistants are compatible?**
A: Any AI assistant that supports the Model Context Protocol, including Claude, ChatGPT (with plugins), Gemini, and other MCP-compatible clients.

**Q: Does this require any modifications to the Hytale server?**
A: No. This is a standard plugin that works with the Nitrado WebServer plugin. No server modifications are needed.

**Q: Can I use this on a production server?**
A: Yes, but ensure you follow security best practices: use strong tokens, enable only necessary features, and restrict permissions appropriately.

### Technical Questions

**Q: What's the performance impact?**
A: Minimal. The plugin only processes requests when AI assistants make calls. Batch operations are optimized to reduce server load.

**Q: Can I add custom tools/features?**
A: Yes! The plugin has an extensible architecture. See the [Extending](#extending-with-custom-features) section for details.

**Q: Is there a limit to how many blocks can be placed at once?**
A: Yes, the `set_blocks_batch` operation has a configurable maximum (default: 1000 blocks) per request to prevent server overload. The `flatten_terrain` tool has a higher limit for large-scale terrain operations.

**Q: Can players have different permission levels?**
A: Yes. You can configure separate permission sets for admin tokens and player tokens, giving you fine-grained control.

## Contributing

We welcome contributions from the community! Here's how you can help:

### Reporting Issues

- Use the [GitHub issue tracker](https://github.com/Metrakit/hytale-MCP-plugin/issues)
- Check if the issue already exists before creating a new one
- Include detailed information: server version, plugin version, error logs

### Submitting Changes

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Test thoroughly
5. Commit with clear messages (`git commit -m 'Add amazing feature'`)
6. Push to your fork (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Copyright (c) 2026 Top-Games

## Support

### Getting Help

- **Issues & Bugs**: [GitHub Issues](https://github.com/Metrakit/hytale-MCP-plugin/issues)

### Useful Links

- [Model Context Protocol Specification](https://modelcontextprotocol.io)
- [Nitrado WebServer Plugin](https://github.com/nitrado/hytale-plugin-webserver)

