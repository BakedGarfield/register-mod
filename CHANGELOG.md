# Changelog

## 0.3.1-beta

- Fixed a compatibility bug where Register Mod kept teleporting unauthenticated players to Worlds’ default lobby spawn instead of the `/setlobby` destination set by Lobby Manager.
- With Lobby Manager installed, Register Mod reads the configured `lobbySpawn` in `config/lobbymanager.json` and waits for Lobby Manager’s delayed teleport before freezing the player at that exact position.
- Does not interfere with Lobby Manager's inventory swaps, `/returntosurvival`, or separate overworld death respawn.
- Without Lobby Manager, retains the old Worlds lobby spawn behavior.
- Updated installation, testing instructions, JAR version, and GitHub Actions output name.


## 0.3.0-beta

- Rebranded to **Register Mod** (`registermod`).
- Updated the companion mod detection to **Lobby Manager** (`lobbymanager`).
- Added all player-facing response, reminder and disconnect messages to `config/registermod.json`, with English defaults and editable prefix.
- Renamed Java package, resource names, output JAR, workflow and project metadata to remove project-specific branding.
- Updated `/register`'s confirmation argument to English.
- Added a full README, complete config example, and explicit account-migration instructions.
- Preserved existing four-digit PIN functionality, spawn locking, rate limits and separate hashed account records.

**Migration:** Existing account files must be copied to `config/registermod/accounts/` before replacing the older mod. No automatic migration is performed.
