# Register Mod

**Register Mod** is a server-side authentication mod for **Minecraft 26.3 (Fabric)**. Players register a four-digit PIN the first time they join and enter their PIN on later visits. Until registration or login succeeds, the mod keeps the player at a configured lobby spawn and blocks most gameplay interactions.

> **Beta / source release:** This project has not been verified on a live server. Make a backup before replacing an existing authentication mod. Four-digit PINs offer **very limited security** and are **not a substitute** for Minecraft's `online-mode=true` or a security-audited authentication solution.

## Features

- **Register a PIN:** New players use `/register <pin> <confirm_pin>` to create an account.
- **Log in:** Returning players use `/login <pin>` on every connection.
- **Login freeze:** Until authenticated, movement packets are blocked and the client is repeatedly corrected to the lobby spawn. Block breaking, item use, entity interaction, item dropping, damage, chat, inventory clicks and unrelated chat commands are restricted by the server-side event handlers/mixins.
- **Automatic lobby handling:** When **Lobby Manager** (`lobbymanager`) is installed, it handles the join teleport and inventory switching. Register Mod reads `lobbySpawn` from `config/lobbymanager.json` and waits until the player reaches **the exact `/setlobby` location** before enforcing the movement lock. It no longer returns players to Worlds’ default spawn. Without Lobby Manager, Register Mod attempts to teleport players directly to the configured lobby dimension.
- **Secure-at-rest PIN hashes:** A separate salt and PBKDF2-HMAC-SHA256 hash are stored for each player. PINs are not stored as readable plaintext.
- **Attempt limits:** Up to five failed PIN checks before a ten-minute lockout, plus a two-second delay between authentication attempts.
- **Editable messages:** All player-visible command responses, reminders, error/kick messages the command usage text, and the message prefix are configurable in English by default.
- **Persistent accounts:** Saved by player UUID in individual JSON files. Existing accounts require a one-time file migration when upgrading from the old project name.

## Commands

| Command | Who can use it | Behavior |
| --- | --- | --- |
| `/register <pin> <confirm_pin>` | New players | Creates the account and **logs the player in immediately**; no additional `/login` is needed on that first join. |
| `/login <pin>` | Registered players | Verifies the PIN, releases movement restrictions and leaves the player in the lobby. |

Example (use your own PIN, not this one):

```mcfunction
/register 5832 5832
/login 5832
```

The PIN must consist of **exactly four digits (0–9)**. You are prompted to log in again the next time you join. There is no built-in command for changing or recovering a forgotten PIN.

**Note:** The `/lobby`, `/buildmode` and `/returntosurvival` commands belong to **Lobby Manager**, not Register Mod. This mod does not create NPCs; any survival/lobby NPC interaction is managed by the separately installed Lobby Manager/Easy NPC setup.

## Installation

1. Run a Fabric server on **Minecraft 26.3**, with Java 25, a compatible Fabric Loader and Fabric API. This source project currently targets Fabric Loader 0.19.5 and Fabric API `0.162.0+26.3`.
2. Install a world-management mod or otherwise create and load the configured `minecraft:lobby` dimension. The project is designed to cooperate with **Worlds** (`mc-worlds`).
3. Optionally install **Lobby Manager**, mod ID `lobbymanager`, on the server if you want its join teleport, separate inventories and return-to-survival features.
4. Put the compiled `register-mod-<version>.jar` into your **server** `mods/` folder. Remove the old authentication JAR before starting; do not run two authentication versions together.
5. Start the server to generate `config/registermod.json` and `config/registermod/accounts/`.
6. Stop the server to edit the config, then restart to load your changes.

### Lobby spawn setup

**If Lobby Manager is installed (recommended):**

1. Enter the lobby dimension (for example, `/worlds tp lobby`).
2. Stand at the exact location where players should appear when joining.
3. Run `/setlobby` as an operator.
4. Verify that `config/lobbymanager.json` contains a non-null `lobbySpawn`, that `lobbyOnJoin` is `true`, and that both mods use the same `lobbyDimension` (`minecraft:lobby` by default).
5. Disconnect and reconnect. Until a successful `/register` or `/login`, Register Mod will lock the player at **these `/setlobby` coordinates**, not at the Worlds spawn.

Lobby Manager saves `/setlobby` immediately. Register Mod reads this saved location for each joining player, so further `/setlobby` changes apply on their next connection. The normal join teleport must finish within `waitForLobbyManagerTicks` (default 100 ticks / approximately 5 seconds). Register Mod does not initiate the teleport or switch inventories when Lobby Manager is present.

**If Lobby Manager is NOT installed:**

Register Mod teleports unauthenticated players to the configured lobby world's regular spawn. If using Worlds, configure it inside the lobby:

```mcfunction
/worlds tp lobby
/worlds settings spawn here
/worlds settings loadOnStartup true
```

The `/setlobby` command belongs to Lobby Manager; without it, Register Mod cannot read a separately configured `/setlobby` location.

## Configuration and message translations

The configuration file is created on first startup:

```text
config/registermod.json
```

A complete configuration with **every editable message** is also included as [`config-example.json`](config-example.json) in this repository. Example:

```json
{
  "lobbyDimension": "minecraft:lobby",
  "teleportDelayTicks": 2,
  "waitForLobbyManagerTicks": 100,
  "positionSyncIntervalTicks": 2,
  "messages": {
    "prefix": "[Register Mod] ",
    "firstJoin": "Welcome! Create a 4-digit PIN with /register <pin> <pin>.",
    "loginSuccessful": "Logged in successfully! Enjoy your stay.",
    "incorrectPin": "Incorrect PIN! {remaining} attempts left before a temporary lock.",
    "accountLocked": "Too many incorrect attempts. Try again in {seconds} seconds."
  }
}
```

The excerpt above is **not the full generated configuration**; see `config-example.json` or the auto-created file for all fields. Edit the text on the right of each message key to use any language or wording you prefer. Do not change the JSON keys. Keep these placeholders in the applicable messages if you want the numbers to appear:

- `{remaining}` — incorrect login attempts still available before temporary lockout.
- `{seconds}` — remaining account-lockout time in seconds.

The message prefix is also editable. Existing message colors are selected by the mod and are not customizable via this config. A missing message key uses its default English text; JSON `null` values and invalid timing values stop the mod from starting so configuration errors are visible.

## Saved accounts and upgrades

New account files are stored in:

```text
config/registermod/accounts/<player-uuid>.json
```

**Important when upgrading an existing server:** Changing the mod's name and internal mod ID also changes its storage directory. Before installing the new JAR:

1. Stop the server and make a complete backup of the server, including its old authentication configuration and `accounts` folder.
2. Copy your existing **account JSON files** from the old authentication mod's `accounts` folder into `config/registermod/accounts/` (create the folder if needed). Do not edit the `salt` or `hash` fields.
3. Recreate any desired old configuration settings in `config/registermod.json` or start once to generate new defaults.
4. Remove the previous JAR and install only the new `register-mod` JAR.
5. Restart and verify that a returning player receives the `/login` prompt rather than being asked to register again.

The new mod does **not** automatically delete or migrate old accounts. Backups and manual migration are required to preserve PINs.

## Building from source

From the repository root, with **JDK 25** and **Gradle 9.7.0** installed:

```sh
gradle clean build
```

Or open **GitHub → Actions → Build Register Mod (Fabric 26.3)** and run the workflow. Download the **`register-mod-jar`** artifact after the build succeeds. The server mod is:

```text
build/libs/register-mod-0.3.1-beta.jar
```

Do **not** install the `-sources.jar` file as a mod. Source compilation and live multiplayer behavior should be tested before public distribution.

## Recommended testing

Test these cases with a **backup server and dummy player accounts/items**, especially when Lobby Manager is also installed:

1. Fresh player joins: arrives exactly at `/setlobby`, not Worlds’ default spawn, and cannot move or interact. Test with `/setlobby` several blocks away from the Worlds spawn.
2. `/register 5832 5832`: creates an account and **immediately releases the player** in the lobby.
3. Player reconnects from survival, the lobby (at an old position), or another dimension: Lobby Manager transfers to `/setlobby` and swaps inventories before the login freeze; survival items and positions remain intact.
4. `/login 5832`: login succeeds and the player's lobby movement works.
5. Wrong PIN, five failed attempts, ten-minute lockout and two-second command cooldown.
6. Missing lobby or unsuccessful teleport: player is disconnected safely.
7. Validate `config/registermod.json` custom messages, and test with several players and server restarts.
8. Check that the previous authentication JAR has been removed and account data has been migrated.

## Limitations and security

A four-digit PIN has only **10,000** possible values; it is unsuitable as strong account protection on a public or offline-mode (`online-mode=false`) server. Keep `online-mode=true` whenever possible. Server mods may have paths that are not covered by the event and packet restrictions included here, and this beta has not received an independent security review. Authentication timeouts and fail-closed disconnects are safety measures, not guarantees against every exploit.

## License

MIT — see [`LICENSE`](LICENSE).
