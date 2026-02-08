<p align="center">
  <img src="ICON.png" alt="Sentinel" width="128" />
</p>

<h1 align="center">Sentinel</h1>

<p align="center">
  A cross-platform Discord account linking and moderation gateway for game servers. Links player accounts to Discord, enforces verification before login, and provides staff moderation tools, all through a shared core that runs on both Velocity (Minecraft) and Hytale.
</p>

<p align="center">
  <a href="USAGE.md">Usage Guide</a>
</p>

## Architecture

Sentinel uses a service locator with adapter pattern to run the same login and moderation logic across multiple game platforms. All shared logic lives in `core/` and depends on platform-independent interfaces; platform entry points wire concrete implementations at startup.

### Service Locator

`SentinelCore.java` holds static references to platform services:

```
SentinelCore.platform()  -> PlatformAdapter
SentinelCore.logger()    -> Logger (SLF4J)
```

### Core Interfaces

All in `world.landfall.sentinel.context`:

| Interface | Purpose |
|-----------|---------|
| `PlatformAdapter` | Player lookup, kick, data directory, scheduler |
| `PlatformPlayer` | Player abstraction (UUID, username, online status) |
| `PlatformScheduler` | Async task scheduling |
| `LoginContext` | Login event data (UUID, username, IP, virtual host, platform) |
| `LoginGatekeeper` | Allow/deny login with platform-specific rendering |
| `GamePlatform` | Enum: `MINECRAFT`, `HYTALE` |

### Login Flow

`LoginHandler` contains all login business logic. Platform listeners extract a `LoginContext` and pass it with a `LoginGatekeeper` to `LoginHandler.handleLogin()`, which runs through:

1. Bypass server check (virtual host routing, Velocity only)
2. Link status check (per-platform via `GamePlatform`)
3. Discord membership verification
4. Quarantine check (with automatic expiry cleanup)
5. ToS acceptance check
6. Allow or deny via `LoginGatekeeper`

Denial reasons are modeled as a `DenialReason` sealed interface with typed variants (`NotLinked`, `Quarantined`, `TosNotAccepted`, `DiscordLeft`, `NeedsRelink`, `ServerError`). Each platform's gatekeeper renders these into its native format.

### Multi-Platform Linking

One Discord account can link both a Minecraft and a Hytale account. The `linked_accounts` table uses a composite primary key `(uuid, platform)` and composite unique `(discord_id, platform)`.

### Shared Business Logic

| Class | Purpose |
|-------|---------|
| `LoginHandler` | Platform-independent login flow (link check, quarantine, ToS, bypass routing) |
| `DatabaseManager` | HikariCP connection pool, all SQL operations, schema migration |
| `DiscordManager` | JDA bot lifecycle, slash command registration, event wiring |
| `LinkCommandListener` | `/link` — claims pending codes, creates account links |
| `WhoIsCommandListener` | `/whois` — lookup by Discord user, Minecraft username, or Hytale username |
| `BanCommandListener` | `/ban` — staff ban with quarantine + kick across all platforms |
| `UnbanCommandListener` | `/unban` — removes bans and quarantines |
| `WarnCommandListener` | `/warn` — staff warnings |
| `NoteCommandListener` | `/note` — internal staff notes |
| `HistoryCommandListener` | `/history` — moderation history lookup |
| `TosCommandListener` | `/tos` — Terms of Service acceptance (optional) |
| `ModerationManager` | Audit logging to Discord channel |
| `RoleManager` | Linked role assignment + bulk sync on startup |
| `QuarantineChecker` | Quarantine validation + automatic expiry cleanup |
| `TosManager` | ToS version tracking and enforcement |
| `ImpersonationManager` | Staff impersonation session tracking |
| `IpLogger` | Login IP audit trail |

## Platform Implementations

### Velocity / Minecraft (`velocity/`)

Built against the Velocity Proxy API. Entry point: `VelocitySentinel` (`@Plugin`).

| Class | Implements | Notes |
|-------|-----------|-------|
| `VelocityPlatformAdapter` | `PlatformAdapter` | Uses `ProxyServer` API |
| `VelocityPlayer` | `PlatformPlayer` | Wraps `Player` |
| `VelocityScheduler` | `PlatformScheduler` | Velocity's async scheduler |
| `VelocityLoginContext` | `LoginContext` | Wraps `LoginEvent`, platform = `MINECRAFT` |
| `VelocityLoginGatekeeper` | `LoginGatekeeper` | Renders `DenialReason` to Adventure Components |
| `VelocityLoginListener` | - | Bridges Velocity login events to `LoginHandler` |
| `VelocityGameProfileListener` | - | Modifies game profile for impersonation (early event) |
| `VelocityImpersonateCommand` | - | `/simulacra` command for staff impersonation |
| `VelocityDisconnectListener` | - | Cleans up impersonation sessions on disconnect |

### Hytale (`hytale/`)

Built against the Hytale Server API (`com.hypixel.hytale.server`). Entry point: `HytaleSentinel extends JavaPlugin`.

| Class | Implements | Notes |
|-------|-----------|-------|
| `HytalePlatformAdapter` | `PlatformAdapter` | Uses `Universe` API, Netty for IP addresses |
| `HytalePlayer` | `PlatformPlayer` | Wraps `PlayerRef` |
| `HytaleScheduler` | `PlatformScheduler` | Timer-based async scheduling |
| `HytaleLoginContext` | `LoginContext` | Wraps `PlayerRef`, platform = `HYTALE` |
| `HytaleLoginGatekeeper` | `LoginGatekeeper` | Renders `DenialReason` as plain ASCII text, kicks after connect |
| `HytaleLoginListener` | - | Bridges Hytale player connect events to `LoginHandler` |
| `HytaleDisconnectListener` | - | Cleanup on disconnect |
| `HytaleLoggerAdapter` | `Logger` (SLF4J) | Adapts Hytale's native logger |

### Hytale Platform Limitations

| Feature | Velocity (Minecraft) | Hytale | Impact |
|---------|---------------------|--------|--------|
| Cancel login event | `LoginEvent.setResult()` | Not available | Must kick after connect via `disconnect()` |
| Rich disconnect messages | Adventure Components (color, formatting, click events) | Plain ASCII text only | No color, no Unicode, no emoji |
| Bypass server routing | Virtual host matching | Not applicable | Hytale gatekeeper returns `false` for `supportsBypassRouting()` |
| Impersonation | Full game profile swap | Not implemented | Velocity-only feature |

## Building

Each module is an independent Gradle project. Build from within each directory:

### Core

```bash
cd core && ./gradlew build
```

### Velocity

```bash
cd velocity && ./gradlew build   # Produces shadow jar in build/libs/
```

### Hytale

Requires a local copy of `HytaleServer.jar`. Set the path in `hytale/gradle.properties`:

```properties
hytale_server_jar=/path/to/HytaleServer.jar
```

```bash
cd hytale && ./gradlew build   # Produces shadow jar in build/libs/
```

## Adding a New Platform

1. Create a new directory (e.g., `paper/`) with its own `build.gradle`, `settings.gradle` (with `includeBuild '../core'`), and Gradle wrapper.
2. Create `platform/<name>/` package under `src/main/java/world/landfall/sentinel/`.
3. Implement: `PlatformAdapter`, `PlatformPlayer`, `PlatformScheduler`, `LoginContext`, `LoginGatekeeper`.
4. Create an entry point that initializes `SentinelCore`, `DatabaseManager`, `DiscordManager`, `LoginHandler`, and registers event listeners.
5. Add the new module to the root `settings.gradle` for IDE discovery.
