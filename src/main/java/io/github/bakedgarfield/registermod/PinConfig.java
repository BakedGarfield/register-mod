package io.github.bakedgarfield.registermod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server settings and all user-facing text. Invalid configurations stop server startup. */
public final class PinConfig {
    public String lobbyDimension = "minecraft:lobby";
    /** Delay before teleporting unauthenticated players if Lobby Manager is absent. */
    public int teleportDelayTicks = 2;
    /** Lobby Manager handles join teleport and inventory swapping, so allow it to finish. */
    public int waitForLobbyManagerTicks = 100;
    /** Send authoritative movement corrections while unauthenticated. */
    public int positionSyncIntervalTicks = 2;

    public Messages messages = new Messages();

    /** Edit these values in config/registermod.json. All defaults are English. */
    public static final class Messages {
        public String prefix = "[Register Mod] ";
        public String registerUsage = "Usage: /register <pin> <confirm_pin>";
        public String loginUsage = "Usage: /login <pin>";
        public String firstJoin = "Welcome! Create a 4-digit PIN with /register <pin> <pin>.";
        public String returningJoin = "Welcome back! Log in with /login <pin>.";
        public String accountReadError = "Authentication data is unavailable. Please contact an administrator.";
        public String loginTimedOut = "Login timed out. Reconnect and use /login.";
        public String lobbyMissing = "The lobby world is not loaded. Please contact an administrator.";
        public String lobbyManagerFailed = "Lobby Manager did not send you to the lobby. Please contact an administrator.";
        public String teleportFailed = "Failed to teleport you to the lobby.";
        public String loginReminder = "You cannot move until you log in. Use /login <pin>.";
        public String registerReminder = "Register to unlock movement: /register <pin> <pin>.";
        public String waitingForLobby = "Please wait until you reach the lobby spawn.";
        public String attemptCooldown = "Please wait a moment before trying again.";
        public String alreadyLoggedIn = "You are already logged in.";
        public String registerPinInvalid = "Use exactly 4 digits (0-9). Example: /register 5832 5832";
        public String pinsDoNotMatch = "The PINs do not match. Try /register again.";
        public String alreadyRegistered = "You already have an account. Use /login <pin>.";
        public String registrationSuccessful = "Registration successful! You are now logged in and can move.";
        public String registrationFailed = "Registration failed. Please contact an administrator.";
        public String alreadyLoggedInLogin = "You are already logged in. Enjoy your stay!";
        public String loginPinInvalid = "Your PIN must contain exactly 4 digits.";
        public String accountMissing = "You do not have an account. Use /register <pin> <pin>.";
        public String accountLocked = "Too many incorrect attempts. Try again in {seconds} seconds.";
        public String lockoutStarted = "Five incorrect attempts: your account is locked for 10 minutes.";
        public String incorrectPin = "Incorrect PIN! {remaining} attempts left before a temporary lock.";
        public String loginSuccessful = "Logged in successfully! Enjoy your stay.";
        public String loginFailed = "Login is unavailable due to a storage error. Please contact an administrator.";
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static PinConfig load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            PinConfig defaults = new PinConfig();
            Files.writeString(path, JSON.toJson(defaults), StandardCharsets.UTF_8);
            return defaults;
        }
        PinConfig config;
        try {
            config = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PinConfig.class);
        } catch (RuntimeException error) {
            throw new IOException("Invalid Register Mod config: " + path, error);
        }
        if (config == null || config.lobbyDimension == null || config.lobbyDimension.isBlank()
                || config.teleportDelayTicks < 1 || config.waitForLobbyManagerTicks < 1
                || config.positionSyncIntervalTicks < 1 || config.positionSyncIntervalTicks > 20
                || config.messages == null) {
            throw new IOException("Invalid Register Mod config: " + path);
        }
        // Reject null message templates; a missing JSON key retains its English default.
        for (var field : Messages.class.getFields()) {
            try {
                if (field.get(config.messages) == null)
                    throw new IOException("Missing message value in Register Mod config: " + field.getName());
            } catch (IllegalAccessException ex) {
                throw new IOException("Cannot read Register Mod message setting: " + field.getName(), ex);
            }
        }
        return config;
    }
}
