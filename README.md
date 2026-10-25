# SupaSpawn

SupaSpawn converts the supplied spawn Skript into a configurable Paper/Purpur plugin.

## Requirements
- Minecraft 1.21.11 (Paper or Purpur)
- Java 21

## Commands
- `/spawn`, `/lobby`, `/hub` — start the configurable countdown to the saved spawn.
- `/spawn 1` — explicitly request Spawn 1.
- `/setspawn` — set the destination at your current position. Permission: `supaspawn.setspawn`.
- `/delspawn` — remove the saved destination. Permission: `supaspawn.delspawn`.

The spawn location persists in `config.yml`. Countdown teleports cancel when the player moves farther than the configured threshold. Teleport tasks are cleaned up on quit and plugin shutdown.

## Configuration
Edit `plugins/SupaSpawn/config.yml` for countdown, movement cancellation, messages, sounds, the spawn-world behavior, first-join welcome messages, starter kit, and optional console commands. Messages support MiniMessage colors including hex colors such as `<#37BFF8>`.

The default first-join messages and starter kit follow the supplied Skript. The external `/dmenu` and `/ibooks` commands are not hard dependencies; configure optional console commands if those plugins are installed.

## Build
Run `mvn -U clean package`. The jar is written to `target/SupaSpawn-1.0.0.jar`. Place it in the server's `plugins` directory and restart the server.
