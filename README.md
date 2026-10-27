# Lobby Manager

A **server-side Fabric mod** for Minecraft **26.3** that sends players to a protected lobby when they join, allows them to return to survival, and keeps the lobby and survival inventories separate. Designed for servers using **Worlds (Multiverse for Fabric)** and optionally **Easy NPC**.

> **Beta source release — build and test before production use.** This repository contains source code, not a verified server-ready JAR. Back up your world and player data before installing or upgrading. Inventory-related mods may be incompatible.

## Features

- **Lobby on join:** players are sent to the custom `/setlobby` location in the lobby dimension when connecting (or the lobby world spawn until `/setlobby` is configured). This happens once per connection after the configured `joinDelayTicks`. Subsequent teleports are not forced back to the lobby.
- **Independent spawns:** `/setlobby` sets the login/lobby teleport point without changing `/setworldspawn` in the Overworld.
- **Overworld respawn after death:** when `respawnAtOverworldSpawn` is enabled, death sends players to the Overworld world spawn set with `/setworldspawn`, never to the lobby; this overrides beds and respawn anchors.
- **Return to survival:** `/returntosurvival` is the recommended safe way to leave the lobby via an NPC. Players can return to their last saved survival position, including a position in the Nether, End, or another loaded dimension. Players without a saved position use the default survival world spawn.
- **Separate inventories:** saves main inventory, armor/equipment/offhand, Ender Chest, XP, hunger, health, and game mode independently for lobby and survival. Survival dimensions share one inventory group.
- **Lobby protection:** enables Adventure Mode for visitors and blocks typical block breaking, block interaction, item use, entity attacks, entity interaction (except for tagged NPCs), damage, dropped items, and pickups. Hunger is kept full.
- **Admin build mode:** an operator can temporarily switch between Creative and Adventure Mode inside the lobby.
- **Custom command messages:** edit all player-facing command feedback in a JSON configuration file. Messages are **English by default**, but can be changed to any language.
- **Automatic saves:** stores player records periodically, on dimension group changes, and on disconnect.

## Commands

| Command | Who can use it | What it does |
| --- | --- | --- |
| `/lobby` | All players | Teleport to the location saved by `/setlobby` (or the lobby world spawn before it is set). |
| `/setlobby` | Operators with Game Master command permissions | Save your current position and look direction as the lobby spawn. Use from inside the configured lobby dimension. |
| `/setworldspawn` | Vanilla Minecraft permission rules | Set the Overworld world spawn **while standing in the Overworld**. This does not change the custom lobby spawn. |
| `/returntosurvival` | All players | Return from the lobby to your saved survival position, or to the default survival spawn. |
| `/buildmode` | Operators with Game Master command permissions | Toggle temporary Creative Mode for building in the lobby. Only works inside the lobby. |

These are top-level commands; there is no required subcommand prefix. If another installed mod provides one of the same commands, it may conflict.

## Requirements

- Minecraft **26.3**
- Fabric Loader **0.19.5** or newer compatible release
- Fabric API **0.162.0+26.3**
- Java **25**
- Worlds (Multiverse for Fabric) for creating and loading the lobby world
- Easy NPC is **optional** for click-to-survival NPCs

The Fabric metadata suggests Worlds, rather than requiring a particular Worlds mod ID; configure and load the lobby dimension before players join.

## Quick setup

1. **Back up your entire server**, including inventory data, world saves, and old mod config data. Test on a copy.
2. Install the built Lobby Manager JAR plus the required Fabric components in the server's `mods/` folder.
3. Create a lobby world using Worlds (if one does not exist):

   ```mcfunction
   /worlds create lobby
   /worlds tp lobby
   ```

4. In the lobby dimension, set it to load at server startup:

   ```mcfunction
   /worlds settings loadOnStartup true
   ```

5. In the lobby world, apply the recommended lobby gamerules below. Commands may depend on your Worlds/Minecraft versions.
6. Start/restart the server. The mod creates **`config/lobbymanager.json`** on first launch. Check that `lobbyDimension` matches the actual world ID, normally `minecraft:lobby`, and restart if you change it.
7. **In the lobby world, stand exactly where players should arrive and run:**

   ```mcfunction
   /setlobby
   ```

   Your precise coordinates and facing direction are written to `config/lobbymanager.json` immediately. This **does not** change the vanilla world spawn.
8. **Go to the Overworld** and set the death-respawn point independently:

   ```mcfunction
   /setworldspawn
   ```

   With `respawnAtOverworldSpawn: true`, deaths always respawn at the Overworld world spawn, including players who die in another dimension. Beds and respawn anchors are ignored for death respawns. The optional `/returntosurvival` NPC still returns players to their previous survival location, except that after dying the saved return location is updated to the Overworld spawn.
9. Join with a test player, confirm joining goes to `/setlobby`, dying in survival respawns at `/setworldspawn`, and returning from the NPC works with the correct inventory.

### Recommended lobby gamerules

Run these while **inside the lobby world**, not in survival:

```mcfunction
/gamerule spawn_mobs false
/gamerule spawn_monsters false
/gamerule pvp false
/gamerule keep_inventory true
/gamerule fire_spread_radius_around_player 0
/gamerule tnt_explodes false
```

Worlds supports world-specific gamerules. **Important:** `noMobSpawning` in the config is only a placeholder setting in this version; the mod does not automatically enforce mob spawn blocking. Use lobby-specific gamerules to do so. Modded spawners and administrative commands may behave differently.

## Easy NPC: clicking an NPC to join survival

An NPC is **optional**. Players can always use `/returntosurvival` directly; the NPC is simply a clickable shortcut.

1. Install an Easy NPC version compatible with your Minecraft/Fabric setup and create a Survival NPC **inside the lobby**.
2. The lobby blocks normal entity interactions. To make your NPC clickable, assign it the configured scoreboard tag. Stand close to the NPC and run:

   ```mcfunction
   /tag @e[sort=nearest,limit=1,type=!minecraft:player] add lobbymanager_npc
   ```

   **Double-check the selected entity.** A more specific `type=` filter is safer when you know the NPC entity type. The string `lobbymanager_npc` must match the `allowedNpcTag` setting in `config/lobbymanager.json`.
3. In the NPC's **Actions** settings, attach the command `returntosurvival` (or `/returntosurvival` if the field requires a slash) to the click action or an NPC dialogue button.
4. Configure the command to **run as the player clicking the NPC**. If run as the NPC or the console, the command will not have the correct player context.
5. Test by clicking the NPC as a **non-operator player**. Confirm that the player returns to survival with the correct inventory.

If an NPC still cannot be clicked, verify its scoreboard tag, interaction settings, and compatibility with Easy NPC. Other mods may handle NPC clicks differently.

## Customizing messages and settings

Edit **`config/lobbymanager.json`** while the server is stopped, then restart the server. The full set of editable messages is included in [`config-example.json`](config-example.json).

For example:

```json
{
  "lobbyDimension": "minecraft:lobby",
  "respawnAtOverworldSpawn": true,
  "messages": {
    "prefix": "[Lobby Manager] ",
    "lobbySuccess": "Welcome to the lobby!",
    "setLobbySuccess": "Lobby position saved at {x}, {y}, {z}.",
    "buildModeEnabled": "You can now build in the lobby.",
    "buildModeDisabled": "Build mode is off.",
    "survivalSuccess": "Have fun in survival!"
  }
}
```

This is an **excerpt**, not a replacement for the entire config. Existing settings can remain unchanged. All default messages are in English; you may translate any value. To hide an individual notification, set its message text to `""`. Change `messages.prefix` to adjust the tag printed before notifications, or set it to `""` for no prefix. The **`{dimension}`** placeholder in `lobbyWorldUnavailable` is replaced by the configured lobby world ID. The **`{x}`**, **`{y}`**, and **`{z}`** placeholders in `setLobbySuccess` display the saved lobby spawn coordinates. Message settings take effect **after a restart**, not immediately.

The `lobbySpawn` position is managed by the **`/setlobby`** command; you do not need to edit its coordinates manually. It is stored in the same JSON file and survives server restarts. When `lobbySpawn` is absent or null, `/lobby` and automatic joins fall back to the lobby world's own spawn. **`respawnAtOverworldSpawn`** defaults to `true`; set it to `false` to restore vanilla death respawn behavior, including beds and respawn anchors. Changes to this Boolean setting require a restart. Neither `/setlobby` nor `/lobby` changes the Overworld spawn, and changing `/setworldspawn` does not move the lobby spawn.

Other settings include `lobbyOnJoin`, `lobbyAdventure`, `noBlockBreak`, `noBlockPlaceOrUse`, `noPlayerAttacks`, `noDamage`, `noHunger`, `noItemPickup`, `noItemDrop`, `separateInventories`, `backupIntervalTicks`, and `joinDelayTicks`.

### Player save files

- Configuration: `config/lobbymanager.json`
- Player records: `config/lobbymanager/players/<uuid>.json`
- NPC tag by default: `lobbymanager_npc`

**Upgrading from an earlier version:** The mod ID, config path, Java package, and player save directory have changed. **Do not delete previous player records.** With the server offline, copy your existing player JSON files from the **previous version's player-save directory** to `config/lobbymanager/players/` and recreate the new settings in `config/lobbymanager.json`. Keep a full backup and verify inventory restoration on a test server first. Renaming the code package alone does not migrate data.

## Troubleshooting: login lands somewhere other than `/setlobby`

1. Confirm `lobbyOnJoin` is `true` in **the server's** `config/lobbymanager.json` (not just `config-example.json`). Restart after editing this setting.
2. Check `lobbyDimension` matches the loaded dimension: `/worlds list loaded`. The default is `minecraft:lobby`. Enable startup loading in that world: `/worlds settings loadOnStartup true`.
3. Run `/worlds tp lobby`, stand where players should arrive, and run `/setlobby`. Verify `lobbySpawn` is no longer `null` in `config/lobbymanager.json`, and that its `world` equals `lobbyDimension`.
4. Test `/lobby` manually. In **0.4.2-beta**, Lobby Manager sends the player to `/setlobby` once, shortly after joining, but **does not** undo later teleports. You can use `/tp`, Worlds teleports, or NPC actions without seeing `Restored ... to /setlobby after another teleport`.
5. Watch the server logs for `Lobby Manager ready`, `Cannot find lobby world`, or `Inventory switch failed`.
6. **Register Safe / authentication compatibility:** if your authentication mod teleports a player after Lobby Manager's one-time join placement, that later teleport now remains in effect. Disable its conflicting post-login teleport or configure that mod to send the player to your saved lobby location. Lobby Manager controls initial join placement; the authentication mod controls when a player can move or use commands.

**Behaviour during login:** `lobbyOnJoin: true` schedules one lobby teleport per connection after `joinDelayTicks` (default 6 ticks). Unlike 0.4.1-beta, it does not continually enforce the lobby until `/returntosurvival`. A teleport made in the first few ticks *before* the scheduled join placement may still be superseded by that one initial placement. Once it has run, ordinary teleports (same world or other dimensions) remain allowed. The lobby/survival inventory sync still runs on world-group transitions. Death respawns remain independent and use the Overworld spawn when `respawnAtOverworldSpawn` is enabled.

**Test after upgrade:** With a backup and a non-admin test account, join (expect `/setlobby`), complete registration/login, test an ordinary `/tp` inside the lobby, use an NPC to run `/returntosurvival`, then return with `/lobby`. Confirm the separate inventories, and check that returning from the Nether/End and respawning after death work as intended.

## Building the mod

This project includes a GitHub Actions workflow and Gradle build files. A Gradle wrapper is **not** included.

- Use Java **25** and a Gradle version compatible with the configured Fabric Loom plugin (the workflow currently uses Gradle **9.7.0**).
- From the project folder, run:

  ```bash
  gradle clean build
  ```

- After a successful build, find `build/libs/lobby-manager-0.4.2-beta.jar`. Use this main JAR in the server's `mods/` folder (not the `-sources.jar`).
- Alternatively, push the source to GitHub, select **Actions**, and run the **Build Fabric 26.3 mod** workflow. Download its JAR artifact after a successful build.

**A successful compile and in-game test are still required.** Dependencies and API methods may need updating if Minecraft/Fabric changes.

## Limitations and safety notes

- Always test first on a backup server, particularly after changing mod IDs or upgrading an existing world.
- An unexpected crash during inventory switching may desynchronize saved snapshots and Minecraft's `player.dat`; automated saves are not a replacement for server backups.
- Other mods may bypass lobby protection callbacks or interfere with custom inventory management.
- When `respawnAtOverworldSpawn` is enabled, the mod explicitly sends dead players to the Overworld spawn; configured beds and respawn anchors will not be used. When disabled, standard Minecraft respawn rules apply.
- If the server does not have a loaded Overworld, the mod logs an error and lets the vanilla respawn location stand.
- NPC compatibility depends on the installed Easy NPC release and its command execution context.
- `noMobSpawning` is currently **not enforced by Java code**; use world-specific gamerules.

## License

MIT — see [`LICENSE`](LICENSE).
