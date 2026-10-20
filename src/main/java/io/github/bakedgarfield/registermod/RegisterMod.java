package io.github.bakedgarfield.registermod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, four-digit PIN registration/login with lobby locking.
 * BETA: PINs are NOT appropriate as strong authentication for offline-mode servers.
 */
public final class RegisterMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("register-mod");
    private static final int MAX_FAILED = 5;
    private static final long LOCKOUT_MS = 10L * 60 * 1000;
    private static final long ATTEMPT_COOLDOWN_MS = 2000;
    private static final int LOGIN_TIMEOUT_TICKS = 120 * 20;
    private static final Set<UUID> AUTHENTICATED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, WaitingPlayer> WAITING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ATTEMPT = new ConcurrentHashMap<>();
    private static PinStore accounts;
    private static PinConfig config;
    private static boolean lobbyManagerInstalled;
    private static long ticks;

    /**
     * This lock records ONLY the lobby spawn, never the survival/logout location.
     * While another mod is switching inventories, do not teleport the player ourselves.
     */
    private static final class WaitingPlayer {
        private boolean ready;
        private double x, y, z;
        private float yaw, pitch;
        private int waitingTicks;

        void lockAtSpawn(ServerLevel lobby) {
            var respawn = lobby.getRespawnData();
            var position = respawn.pos();
            x = position.getX() + 0.5;
            y = position.getY();
            z = position.getZ() + 0.5;
            yaw = respawn.yaw();
            pitch = respawn.pitch();
            ready = true;
        }
    }

    public static boolean isAuthenticated(ServerPlayer player) {
        return player != null && AUTHENTICATED.contains(player.getUUID());
    }

    public static boolean requiresLogin(Player player) {
        return player instanceof ServerPlayer serverPlayer && !isAuthenticated(serverPlayer);
    }

    @Override
    public void onInitialize() {
        try {
            accounts = new PinStore(FabricLoader.getInstance().getConfigDir()
                    .resolve("registermod").resolve("accounts"));
            config = PinConfig.load(FabricLoader.getInstance().getConfigDir()
                    .resolve("registermod.json"));
            lobbyManagerInstalled = FabricLoader.getInstance().isModLoaded("lobbymanager");
        } catch (IOException error) {
            // Never allow a server to run with a nonfunctional credential database.
            throw new IllegalStateException("PIN account storage unavailable: refusing startup", error);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("register")
                    .executes(ctx -> showUsage(ctx.getSource().getPlayerOrException(), config.messages.registerUsage))
                    .then(Commands.argument("pin", StringArgumentType.word())
                            .executes(ctx -> showUsage(ctx.getSource().getPlayerOrException(), config.messages.registerUsage))
                            .then(Commands.argument("confirmation", StringArgumentType.word())
                                    .executes(ctx -> register(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "pin"),
                                            StringArgumentType.getString(ctx, "confirmation"))))));
            dispatcher.register(Commands.literal("login")
                    .executes(ctx -> showUsage(ctx.getSource().getPlayerOrException(), config.messages.loginUsage))
                    .then(Commands.argument("pin", StringArgumentType.word())
                            .executes(ctx -> login(ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "pin")))));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID id = player.getUUID();
            AUTHENTICATED.remove(id);
            LAST_ATTEMPT.remove(id);
            WAITING.put(id, new WaitingPlayer());
            try {
                // Decode now, so broken files never become a new account.
                PinStore.Account account = accounts.load(id);
                if (account == null) notifyPlayer(player,
                        config.messages.firstJoin, ChatFormatting.GOLD);
                else notifyPlayer(player,
                        config.messages.returningJoin, ChatFormatting.YELLOW);
            } catch (IOException error) {
                LOG.error("Could not load PIN account {}", id, error);
                player.connection.disconnect(Component.literal(config.messages.accountReadError));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            AUTHENTICATED.remove(id);
            WAITING.remove(id);
            LAST_ATTEMPT.remove(id);
        });
        ServerTickEvents.END_SERVER_TICK.register(RegisterMod::tick);

        // Basic player-interaction lockdown while a PIN is requested.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> !requiresLogin(player));
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                requiresLogin(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                requiresLogin(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                requiresLogin(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                requiresLogin(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, chatType) -> !requiresLogin(sender));
        LOG.info("Register Mod loaded (26.3). Lobby {}, Lobby Manager installed: {}. WARNING: 4-digit PINs are weak.",
                config.lobbyDimension, lobbyManagerInstalled);
    }

    /** True only when Worlds has the configured lobby dimension loaded. */
    private static ServerLevel findLobby(MinecraftServer server) {
        try {
            ResourceKey<Level> key = ResourceKey.create(
                    Registries.DIMENSION, Identifier.parse(config.lobbyDimension));
            return server.getLevel(key);
        } catch (IllegalArgumentException ex) {
            LOG.error("Invalid lobby dimension {}", config.lobbyDimension, ex);
            return null;
        }
    }

    private static boolean inLobby(ServerPlayer player) {
        return config.lobbyDimension.equals(player.level().dimension().identifier().toString());
    }

    private static void lockToSpawn(ServerPlayer player, WaitingPlayer wait, ServerLevel lobby) {
        if (!wait.ready) wait.lockAtSpawn(lobby);
        player.setDeltaMovement(0, 0, 0);
        // IMPORTANT: handleMovePlayer is cancelled before it can update server-side
        // coordinates. Therefore distanceToSqr() stays near zero EVEN when the
        // client predicts walking. Actively resynchronize the client at intervals.
        // This runs on the SERVER tick thread (not the network packet thread).
        if (ticks % config.positionSyncIntervalTicks == 0
                || player.distanceToSqr(wait.x, wait.y, wait.z) > 0.015) {
            player.connection.teleport(wait.x, wait.y, wait.z, wait.yaw, wait.pitch);
        }
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        ServerLevel lobby = findLobby(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isAuthenticated(player)) continue;
            WaitingPlayer wait = WAITING.computeIfAbsent(player.getUUID(), key -> new WaitingPlayer());
            wait.waitingTicks++;
            if (wait.waitingTicks >= LOGIN_TIMEOUT_TICKS) {
                player.connection.disconnect(Component.literal(config.messages.loginTimedOut));
                continue;
            }
            if (lobby == null) {
                // Fail closed: an unconfigured/unloaded lobby must not expose survival.
                player.connection.disconnect(Component.literal(config.messages.lobbyMissing));
                continue;
            }
            if (!inLobby(player)) {
                if (lobbyManagerInstalled) {
                    // CRITICAL: Lobby Manager owns the join teleport + inventory group swap.
                    // Teleporting first would make it skip the survival-to-lobby inventory swap.
                    if (wait.ready || wait.waitingTicks > config.waitForLobbyManagerTicks) {
                        player.connection.disconnect(Component.literal(
                                config.messages.lobbyManagerFailed));
                    }
                } else if (wait.waitingTicks >= config.teleportDelayTicks) {
                    wait.lockAtSpawn(lobby);
                    boolean moved = player.teleportTo(lobby, wait.x, wait.y, wait.z,
                            Set.of(), wait.yaw, wait.pitch, false);
                    if (!moved) {
                        player.connection.disconnect(Component.literal(config.messages.teleportFailed));
                        LOG.error("Failed to teleport unauthenticated player {} to {}",
                                player.getUUID(), config.lobbyDimension);
                    }
                }
                continue;
            }
            // In lobby: first lock to Worlds' configured spawn, then prevent movement.
            lockToSpawn(player, wait, lobby);
            if (ticks % 200 == 0) {
                try {
                    if (accounts.exists(player.getUUID()))
                        notifyPlayer(player, config.messages.loginReminder, ChatFormatting.YELLOW);
                    else
                        notifyPlayer(player, config.messages.registerReminder, ChatFormatting.GOLD);
                } catch (RuntimeException error) {
                    LOG.warn("Could not display auth reminder", error);
                }
            }
        }
    }

    /** Prevent authentication from succeeding while the player is still in survival or being teleported. */
    private static boolean readyForAuthentication(ServerPlayer player) {
        WaitingPlayer wait = WAITING.get(player.getUUID());
        if (wait != null && wait.ready && inLobby(player)) return true;
        notifyPlayer(player, config.messages.waitingForLobby, ChatFormatting.YELLOW);
        return false;
    }

    private static boolean cooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long previous = LAST_ATTEMPT.get(player.getUUID());
        if (previous != null && now - previous < ATTEMPT_COOLDOWN_MS) {
            notifyPlayer(player, config.messages.attemptCooldown, ChatFormatting.RED);
            return true;
        }
        LAST_ATTEMPT.put(player.getUUID(), now);
        return false;
    }

    private static int register(ServerPlayer player, String pin, String confirmation) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(player)) {
            notifyPlayer(player, config.messages.alreadyLoggedIn, ChatFormatting.YELLOW);
            return 0;
        }
        if (!readyForAuthentication(player)) return 0;
        if (cooldown(player)) return 0;
        if (!PinCrypto.valid(pin) || !PinCrypto.valid(confirmation)) {
            notifyPlayer(player, config.messages.registerPinInvalid, ChatFormatting.RED);
            return 0;
        }
        if (!pin.equals(confirmation)) {
            notifyPlayer(player, config.messages.pinsDoNotMatch, ChatFormatting.RED);
            return 0;
        }
        try {
            if (accounts.exists(uuid)) {
                notifyPlayer(player, config.messages.alreadyRegistered, ChatFormatting.YELLOW);
                return 0;
            }
            PinStore.Account newAccount = new PinStore.Account();
            newAccount.salt = PinCrypto.newSalt();
            newAccount.hash = PinCrypto.hash(pin, newAccount.salt);
            accounts.create(uuid, newAccount);
            AUTHENTICATED.add(uuid);
            WAITING.remove(uuid);
            LAST_ATTEMPT.remove(uuid);
            notifyPlayer(player, config.messages.registrationSuccessful, ChatFormatting.GREEN);
            return 1;
        } catch (IOException | GeneralSecurityException error) {
            LOG.error("Could not register PIN for {}", uuid, error);
            notifyPlayer(player, config.messages.registrationFailed, ChatFormatting.RED);
            return 0;
        }
    }

    private static int login(ServerPlayer player, String pin) {
        UUID uuid = player.getUUID();
        if (isAuthenticated(player)) {
            notifyPlayer(player, config.messages.alreadyLoggedInLogin, ChatFormatting.GREEN);
            return 1;
        }
        if (!readyForAuthentication(player)) return 0;
        if (cooldown(player)) return 0;
        if (!PinCrypto.valid(pin)) {
            notifyPlayer(player, config.messages.loginPinInvalid, ChatFormatting.RED);
            return 0;
        }
        try {
            PinStore.Account account = accounts.load(uuid);
            if (account == null) {
                notifyPlayer(player, config.messages.accountMissing, ChatFormatting.GOLD);
                return 0;
            }
            long now = System.currentTimeMillis();
            if (now < account.lockedUntilEpochMs) {
                long seconds = (account.lockedUntilEpochMs - now + 999) / 1000;
                notifyPlayer(player, config.messages.accountLocked.replace("{seconds}", Long.toString(seconds)), ChatFormatting.RED);
                return 0;
            }
            boolean correct = PinCrypto.matches(pin, account.salt, account.hash);
            if (!correct) {
                account.failedAttempts++;
                if (account.failedAttempts >= MAX_FAILED) {
                    account.failedAttempts = 0;
                    account.lockedUntilEpochMs = now + LOCKOUT_MS;
                    notifyPlayer(player, config.messages.lockoutStarted, ChatFormatting.RED);
                } else {
                    notifyPlayer(player, config.messages.incorrectPin.replace("{remaining}", Integer.toString(MAX_FAILED - account.failedAttempts)), ChatFormatting.RED);
                }
                accounts.update(uuid, account);
                return 0;
            }
            account.failedAttempts = 0;
            account.lockedUntilEpochMs = 0;
            // Only authenticate after the updated account was persisted.
            accounts.update(uuid, account);
            AUTHENTICATED.add(uuid);
            WAITING.remove(uuid);
            LAST_ATTEMPT.remove(uuid);
            notifyPlayer(player, config.messages.loginSuccessful, ChatFormatting.GREEN);
            return 1;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException error) {
            LOG.error("PIN verification failed for {}", uuid, error);
            notifyPlayer(player, config.messages.loginFailed, ChatFormatting.RED);
            return 0;
        }
    }

    private static int showUsage(ServerPlayer player, String text) {
        notifyPlayer(player, text, ChatFormatting.YELLOW);
        return 0;
    }

    private static void notifyPlayer(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(config.messages.prefix + text).withStyle(color));
    }

    /** Used by the server-packet mixin to reject commands before login. */
    public static boolean allowedCommand(ServerPlayer player, String raw) {
        if (isAuthenticated(player)) return true;
        if (raw == null) return false;
        String command = raw.strip();
        if (command.startsWith("/")) command = command.substring(1);
        int split = command.indexOf(' ');
        String root = (split < 0 ? command : command.substring(0, split));
        return root.equalsIgnoreCase("register") || root.equalsIgnoreCase("login");
    }
}
