# Sentinel - User Guide

Sentinel is a cross-platform Discord account linking and moderation gateway for game servers. It requires players to link their game account to Discord before joining, and provides staff with moderation tools through Discord slash commands.

## Features

*   **Account Linking:**
    *   Players must link their game account to Discord before joining.
    *   One Discord account can link both a Minecraft and a Hytale account.
    *   Link codes are generated automatically when an unlinked player tries to join.
*   **Login Protection:**
    *   Unlinked players are denied with a link code and instructions.
    *   Players who leave the Discord server are automatically unlinked.
    *   Configurable bypass servers for lobbies or auth servers (Velocity only).
*   **Quarantine System:**
    *   Staff can quarantine players with optional duration and reason.
    *   Quarantined players are immediately kicked from all linked platforms.
    *   Automatic cleanup of expired quarantines on login.
*   **Moderation Tools:**
    *   Bans, warnings, and internal staff notes — all through Discord.
    *   Full moderation history per player.
    *   Audit logging to a designated Discord channel.
*   **Role Management:**
    *   Automatic Discord role assignment for linked players.
    *   Bulk role sync on bot startup.
*   **Terms of Service (Optional):**
    *   Clickwrap ToS system with version tracking.
    *   Players must accept current ToS version to join.
    *   Audit trail of ToS acceptances.
*   **IP Logging (Optional):**
    *   Logs login attempts with IP address, linked Discord ID, and allow/deny status.
*   **Impersonation (Velocity Only):**
    *   Staff can impersonate other players' game profiles for debugging locked inventories or banned accounts.

## Commands

All commands are Discord slash commands unless noted otherwise.

### Player Commands

*   `/link <code>` - Link your game account to your Discord account using the code shown on the kick screen.
*   `/tos` - View and accept the current Terms of Service (if ToS enforcement is enabled).

### Staff Lookup Commands

*   `/whois discord:@user` - Look up all linked accounts for a Discord user.
*   `/whois minecraft:<username>` - Look up a player by their Minecraft username.
*   `/whois hytale:<username>` - Look up a player by their Hytale username.
*   `/history <@user>` - View a player's full moderation history (notes, warnings, bans).

### Staff Moderation Commands

*   `/note <@user> <text>` - Add an internal staff note to a player's record.
*   `/warn <@user> <reason>` - Issue a formal warning to a player.
*   `/ban <@user> [duration] <reason>` - Ban a player. Applies quarantine role and kicks from all linked platforms.
*   `/unban <@user>` - Remove a ban from a player.

### In-Game Commands (Velocity Only)

*   `/simulacra <username>` (alias: `/sim`) - Impersonate a player's game profile. Staff only.

### Duration Format

Duration arguments accept the following format:

| Unit | Example | Meaning |
|------|---------|---------|
| `s` | `30s` | 30 seconds |
| `m` | `15m` | 15 minutes |
| `h` | `2h` | 2 hours |
| `d` | `7d` | 7 days |
| `w` | `2w` | 2 weeks |

Omitting the duration on `/ban` makes it permanent.

## Setup

### Requirements

*   Java 17+ (Java 21 for Hytale)
*   MySQL or MariaDB
*   A Discord bot token with the **Guild Members** intent enabled

### Installation

1.  **Velocity (Minecraft):** Place the shadow JAR into your Velocity proxy's `plugins/` folder.
2.  **Hytale:** Place the shadow JAR into your Hytale server's `plugins/` folder.

### Configuration

On first run, `config.json` is created in the plugin's data directory. Fill in your credentials:

```json
{
  "mysql": {
    "host": "localhost",
    "port": 3306,
    "database": "sentinel",
    "username": "sentinel_user",
    "password": "change_me"
  },
  "discord": {
    "token": "your_discord_bot_token",
    "linkedRole": "",
    "quarantineRole": "",
    "quarantineMessage": "Your account has been quarantined. Contact an administrator.",
    "staffRoles": [],
    "tosAuditChannel": "",
    "moderationAuditChannel": ""
  },
  "tos": {
    "enforcement": false,
    "version": "1.0.0",
    "url": "",
    "content": "",
    "ipLogging": true
  },
  "bypassServers": {
    "servers": []
  },
  "impersonation": {
    "enabled": false,
    "allowedUsers": []
  }
}
```

### Configuration Reference

#### `mysql`

Database connection settings. Sentinel uses HikariCP for connection pooling.

#### `discord`

*   `token` - Your Discord bot token.
*   `linkedRole` - (Optional) Discord role ID to automatically assign to all linked players. Role sync runs on startup and applies immediately on new links.
*   `quarantineRole` - (Optional) Discord role ID applied when a player is quarantined. Used for Discord channel restrictions. Login blocking is handled by the database, not this role.
*   `quarantineMessage` - Default message shown to quarantined players.
*   `staffRoles` - Array of Discord role IDs that can use moderation commands (`/ban`, `/warn`, `/note`, `/history`, etc.).
*   `tosAuditChannel` - (Optional) Discord channel ID for ToS acceptance audit logs.
*   `moderationAuditChannel` - (Optional) Discord channel ID for moderation action audit logs.

#### `tos`

*   `enforcement` - Set to `true` to require ToS acceptance before login.
*   `version` - Current ToS version string. Changing this requires all players to re-accept.
*   `url` - URL to the full Terms of Service document.
*   `content` - ToS content displayed in the `/tos` Discord command.
*   `ipLogging` - Set to `true` to log login attempt IP addresses.

#### `bypassServers` (Velocity only)

*   `servers` - Array of server names that bypass the linking requirement. Useful for lobby or auth servers where unverified players should be allowed. Matched against the virtual host.

#### `impersonation` (Velocity only)

*   `enabled` - Set to `true` to enable the `/simulacra` command.
*   `allowedUsers` - Array of Minecraft UUIDs permitted to use impersonation.

## How Linking Works

1.  A player tries to join the game server.
2.  Sentinel checks if their game account is linked to Discord for that platform.
3.  If not linked, the player is kicked with a 6-character link code and instructions.
4.  The player runs `/link <code>` in the Discord server.
5.  Their accounts are linked and they can join immediately.

A single Discord account can hold one Minecraft link and one Hytale link simultaneously.

## How Bans Work

*   Staff run `/ban @user duration:2h reason:"Rule violation"` in Discord.
*   The player is immediately kicked from the game server (Minecraft/Hytale).
*   The player cannot rejoin until the ban expires or is manually removed with `/unban`.
*   Expired bans are automatically cleaned up on the next login attempt.
*   The quarantine role (if configured) is applied/removed on the Discord side for channel restrictions.
