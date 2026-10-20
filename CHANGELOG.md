# Changelog

## 0.3.0-beta

- Rebranded to **Register Mod** (`registermod`).
- Updated the companion mod detection to **Lobby Manager** (`lobbymanager`).
- Added all player-facing response, reminder and disconnect messages to `config/registermod.json`, with English defaults and editable prefix.
- Renamed Java package, resource names, output JAR, workflow and project metadata to remove project-specific branding.
- Updated `/register`'s confirmation argument to English.
- Added a full README, complete config example, and explicit account-migration instructions.
- Preserved existing four-digit PIN functionality, spawn locking, rate limits and separate hashed account records.

**Migration:** Existing account files must be copied to `config/registermod/accounts/` before replacing the older mod. No automatic migration is performed.
