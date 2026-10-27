package io.github.bakedgarfield.lobbymanager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lobby Manager: auto lobby on login, persistent dimension-safe /returntosurvival,
 * separate inventory groups and protection. Dedicated server only.
 * TEST WITH BACKUPS before installing in a real survival server.
 */
public final class LobbyManager implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("lobbymanager");
    private static Config config;
    private static PlayerStore store;
    private static final Map<UUID, PlayerRecord> records = new HashMap<>();
    private static final Map<UUID, Integer> pendingJoin = new HashMap<>();
    // The login teleport happens once after joinDelayTicks. Subsequent teleports
    // (including /tp, Worlds commands and NPC actions) are never force-reversed.
    private static final Set<UUID> buildMode = new HashSet<>();
    private static long ticks;

    @Override
    public void onInitialize() {
        try {
            config = Config.load();
        } catch (IOException e) {
            throw new IllegalStateException("Lobby Manager: cannot load config; refusing to run", e);
        }
        store = new PlayerStore(LOG);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> commands(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler.getPlayer(), server));
        ServerTickEvents.END_SERVER_TICK.register(LobbyManager::onTick);
        // The join event is NOT a death-respawn event. Keep those paths independent.
        ServerPlayerEvents.AFTER_RESPAWN.register(LobbyManager::onRespawn);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) saveOnline(player, server);
        });

        // These Fabric events only affect the lobby, not survival/the Nether/the End.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                !config.noBlockBreak || !restricted(player));
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                config.noBlockPlaceOrUse && restricted(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                config.noBlockPlaceOrUse && restricted(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                config.noPlayerAttacks && restricted(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!restricted(player)) return InteractionResult.PASS;
            // Only NPCs with the configured tag are interactable inside the protected lobby.
            return entity.entityTags().contains(config.allowedNpcTag)
                    ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        LOG.info("Lobby Manager ready: lobby dimension {}", config.lobbyDimension);
    }

    public static boolean isLobby(ServerPlayer player) {
        return config != null && config.lobbyDimension.equals(player.level().dimension().identifier().toString());
    }

    public static boolean restricted(net.minecraft.world.entity.player.Player player) {
        return player instanceof ServerPlayer serverPlayer && isLobby(serverPlayer)
                && !buildMode.contains(serverPlayer.getUUID());
    }

    public static boolean noLobbyDamage(ServerPlayer player) {
        return config != null && config.noDamage && restricted(player);
    }

    public static boolean noLobbyDrop(ServerPlayer player) {
        return config != null && config.noItemDrop && restricted(player);
    }

    public static boolean noLobbyPickup(net.minecraft.world.entity.player.Player player) {
        return config != null && config.noItemPickup && restricted(player);
    }

    private static void onJoin(ServerPlayer player) {
        try {
            PlayerRecord record = store.load(player.getUUID());
            records.put(player.getUUID(), record);
            if (config.lobbyOnJoin) {
                pendingJoin.put(player.getUUID(), Math.max(1, config.joinDelayTicks));
            }
        } catch (IOException ex) {
            LOG.error("Blocking lobby conversion because {} save could not be loaded", player.getName().getString(), ex);
            sendMessage(player, config.messages.playerDataLoadFailed);
            // Do NOT create a fresh empty inventory on corrupt/invalid saves.
        }
    }

    private static void onLeave(ServerPlayer player, MinecraftServer server) {
        pendingJoin.remove(player.getUUID());
        saveOnline(player, server);
        records.remove(player.getUUID());
        buildMode.remove(player.getUUID());
    }

    private static void onTick(MinecraftServer server) {
        ticks++;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            UUID uuid = player.getUUID();
            PlayerRecord record = records.get(uuid);
            if (record == null) continue; // no inventory modification on load error
            Integer delay = pendingJoin.get(uuid);
            if (delay != null) {
                if (delay > 1) {
                    pendingJoin.put(uuid, delay - 1);
                    continue;
                }
                pendingJoin.remove(uuid);
                // Exactly one login-lobby teleport per connection. Once placed,
                // players are free to use any other server-authorized teleport.
                if (config.lobbyOnJoin) joinLobby(player, record, server);
                continue;
            }

            boolean lobby = isLobby(player);
            String liveGroup = lobby ? "LOBBY" : "SURVIVAL";
            // Detect dimension travel done by Worlds commands, portals, or other mods.
            if (!record.activeGroup.equals(liveGroup) && config.separateInventories) {
                switchGroup(player, record, liveGroup, server);
            }
            if (!lobby) {
                record.lastSurvival = PlayerRecord.location(player);
            } else if (!buildMode.contains(uuid)) {
                if (config.lobbyAdventure && player.gameMode() != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
                if (config.noHunger) {
                    player.getFoodData().setFoodLevel(20);
                    player.getFoodData().setSaturation(20f);
                }
                if (config.noDamage) {
                    player.setRemainingFireTicks(0);
                    player.setAirSupply(player.getMaxAirSupply());
                }
            }
        }
        if (ticks % Math.max(20, config.backupIntervalTicks) == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) saveOnline(player, server);
        }
    }

    /** Respawning after death uses the vanilla Overworld world spawn, never /setlobby. */
    private static void onRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (alive) return;
        pendingJoin.remove(newPlayer.getUUID());
        if (!config.respawnAtOverworldSpawn) return;
        buildMode.remove(newPlayer.getUUID());
        MinecraftServer server = newPlayer.level().getServer();
        ServerLevel overworld = world(server, "minecraft:overworld");
        if (overworld == null) {
            LOG.error("Cannot respawn {} in Overworld: dimension is not loaded", newPlayer.getName().getString());
            sendMessage(newPlayer, config.messages.respawnWorldUnavailable);
            return;
        }
        var spawn = overworld.getRespawnData();
        BlockPos pos = spawn.pos();
        if (!newPlayer.teleportTo(overworld, pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                Set.of(), spawn.yaw(), spawn.pitch(), false)) {
            LOG.error("Could not teleport {} to the Overworld after death", newPlayer.getName().getString());
            return;
        }
        PlayerRecord record = records.get(newPlayer.getUUID());
        if (record == null) return; // Never replace inventories on a corrupt/unreadable save.
        if (config.separateInventories && "LOBBY".equals(record.activeGroup)) {
            // The respawned player carries the post-death lobby inventory; restore survival.
            if (!switchGroup(newPlayer, record, "SURVIVAL", server)) return;
        } else if (!config.separateInventories) {
            // With inventory separation disabled, all worlds share the live inventory.
            record.activeGroup = "SURVIVAL";
        }
        record.lastSurvival = PlayerRecord.location(newPlayer);
        try {
            // Preserve the actual post-death survival inventory (drops / keepInventory).
            // Otherwise an old snapshot could bring back dropped items after a lobby visit.
            if ("SURVIVAL".equals(record.activeGroup)) {
                record.survival = PlayerRecord.InventorySnapshot.capture(newPlayer, server.registryAccess());
            }
            store.save(newPlayer.getUUID(), record);
        } catch (Exception e) {
            LOG.error("Could not save post-death data for {}", newPlayer.getName().getString(), e);
        }
    }

    private static boolean joinLobby(ServerPlayer player, PlayerRecord record, MinecraftServer server) {
        ServerLevel lobby = world(server, config.lobbyDimension);
        if (lobby == null) {
            sendMessage(player, config.messages.lobbyWorldUnavailable);
            LOG.warn("Cannot find lobby world {}. No inventory swap performed", config.lobbyDimension);
            return false;
        }
        // Save actual inventory only if it belongs to the active group.
        // On startup, a player may appear in lobby with an old player.dat; don't overwrite survival with lobby inventory.
        if (!isLobby(player) && "SURVIVAL".equals(record.activeGroup)) {
            record.lastSurvival = PlayerRecord.location(player);
        }
        PlayerRecord.Position configuredSpawn = config.lobbySpawn;
        double x, y, z;
        float yaw, pitch;
        if (configuredSpawn != null && config.lobbyDimension.equals(configuredSpawn.world())) {
            x = configuredSpawn.x(); y = configuredSpawn.y(); z = configuredSpawn.z();
            yaw = configuredSpawn.yaw(); pitch = configuredSpawn.pitch();
        } else {
            // Keep older installations working until an operator runs /setlobby.
            var respawn = lobby.getRespawnData();
            var spawn = respawn.pos();
            x = spawn.getX() + .5; y = spawn.getY(); z = spawn.getZ() + .5;
            yaw = respawn.yaw(); pitch = respawn.pitch();
        }
        if (isLobby(player)) {
            if (config.separateInventories && !"LOBBY".equals(record.activeGroup)) {
                // This situation can arise after crash recovery. Do NOT guess the inventory owner.
                LOG.warn("{} is in lobby but file states SURVIVAL; preserving item data", player.getName().getString());
            }
            boolean moved = player.teleportTo(lobby, x, y, z,
                    Set.of(), yaw, pitch, false);
            if (!moved) sendMessage(player, config.messages.lobbyTeleportFailed);
            return moved;
        }
        // Save inventory before teleport, then switch group, never invent one on a data error.
        if (config.separateInventories && "SURVIVAL".equals(record.activeGroup)) {
            if (!switchGroup(player, record, "LOBBY", server)) return false;
        }
        boolean ok = player.teleportTo(lobby, x, y, z,
                Set.of(), yaw, pitch, false);
        if (!ok) {
            LOG.warn("Teleport to lobby rejected for {}", player.getName().getString());
            sendMessage(player, config.messages.lobbyTeleportFailed);
        }
        return ok;
    }

    private static boolean switchGroup(ServerPlayer player, PlayerRecord record, String targetGroup, MinecraftServer server) {
        if (record.activeGroup.equals(targetGroup)) return true;
        PlayerRecord.InventorySnapshot incoming = targetGroup.equals("LOBBY") ? record.lobby : record.survival;
        if (incoming == null) {
            incoming = PlayerRecord.InventorySnapshot.empty(player);
        }
        try {
            // Snapshot current equipment / ender chest / xp before changing anything.
            PlayerRecord.InventorySnapshot current = PlayerRecord.InventorySnapshot.capture(player, server.registryAccess());
            // Validate and decode incoming stack data BEFORE touching current inventory.
            // The restore call parses all slots and only then applies them.
            if ("LOBBY".equals(record.activeGroup)) record.lobby = current;
            else record.survival = current;
            store.save(player.getUUID(), record);
            incoming.restore(player, server.registryAccess(), targetGroup.equals("LOBBY"));
            if (targetGroup.equals("LOBBY")) record.lobby = incoming;
            else record.survival = incoming;
            record.activeGroup = targetGroup;
            store.save(player.getUUID(), record);
            return true;
        } catch (Exception e) {
            LOG.error("Inventory switch failed for {}: no further swaps", player.getName().getString(), e);
            sendMessage(player, config.messages.inventorySwapFailed);
            return false;
        }
    }

    private static void saveOnline(ServerPlayer player, MinecraftServer server) {
        PlayerRecord record = records.get(player.getUUID());
        if (record == null) return;
        try {
            if ("LOBBY".equals(record.activeGroup) && isLobby(player)) {
                record.lobby = PlayerRecord.InventorySnapshot.capture(player, server.registryAccess());
            } else if ("SURVIVAL".equals(record.activeGroup) && !isLobby(player)) {
                record.lastSurvival = PlayerRecord.location(player);
                record.survival = PlayerRecord.InventorySnapshot.capture(player, server.registryAccess());
            }
            store.save(player.getUUID(), record);
        } catch (Exception e) {
            LOG.error("Could not save {}. Preserve backups before retrying!", player.getName().getString(), e);
        }
    }

    private static ServerLevel world(MinecraftServer server, String dimension) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid dimension ID: {}", dimension, e);
            return null;
        }
    }

    private static void sendMessage(ServerPlayer player, String message) {
        // Blank messages can be used to disable a notification in the config.
        if (message == null || message.isBlank()) return;
        String prefix = config.messages.prefix == null ? "" : config.messages.prefix;
        String formatted = message.replace("{dimension}", config.lobbyDimension);
        if (config.lobbySpawn != null) {
            formatted = formatted.replace("{x}", String.format(java.util.Locale.ROOT, "%.2f", config.lobbySpawn.x()))
                    .replace("{y}", String.format(java.util.Locale.ROOT, "%.2f", config.lobbySpawn.y()))
                    .replace("{z}", String.format(java.util.Locale.ROOT, "%.2f", config.lobbySpawn.z()));
        }
        player.sendSystemMessage(Component.literal(prefix + formatted));
    }

    private static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("returntosurvival").executes(LobbyManager::returnToSurvival));
        dispatcher.register(Commands.literal("setlobby")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> setLobby(ctx.getSource().getPlayerOrException())));
        dispatcher.register(Commands.literal("lobby").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            PlayerRecord record = records.get(player.getUUID());
            if (record == null) {
                sendMessage(player, config.messages.playerDataUnavailable);
                return 0;
            }
            if (!joinLobby(player, record, ctx.getSource().getServer())) return 0;
            sendMessage(player, config.messages.lobbySuccess);
            return 1;
        }));
        dispatcher.register(Commands.literal("buildmode")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> toggleBuild(ctx.getSource().getPlayerOrException())));
    }

    private static int setLobby(ServerPlayer player) {
        if (!isLobby(player)) {
            sendMessage(player, config.messages.setLobbyWrongWorld);
            return 0;
        }
        PlayerRecord.Position oldSpawn = config.lobbySpawn;
        config.lobbySpawn = PlayerRecord.location(player);
        try {
            config.save();
            sendMessage(player, config.messages.setLobbySuccess);
            return 1;
        } catch (IOException e) {
            config.lobbySpawn = oldSpawn;
            LOG.error("Failed to save lobby spawn location", e);
            sendMessage(player, config.messages.setLobbySaveFailed);
            return 0;
        }
    }

    private static int toggleBuild(ServerPlayer player) {
        if (!isLobby(player)) {
            sendMessage(player, config.messages.buildModeLobbyOnly);
            return 0;
        }
        if (buildMode.remove(player.getUUID())) {
            player.setGameMode(GameType.ADVENTURE);
            sendMessage(player, config.messages.buildModeDisabled);
        } else {
            buildMode.add(player.getUUID());
            player.setGameMode(GameType.CREATIVE);
            sendMessage(player, config.messages.buildModeEnabled);
        }
        return 1;
    }

    private static int returnToSurvival(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerRecord record = records.get(player.getUUID());
        if (record == null) {
            sendMessage(player, config.messages.playerDataUnavailable);
            return 0;
        }
        if (!isLobby(player)) {
            sendMessage(player, config.messages.survivalLobbyOnly);
            return 0;
        }
        var position = record.lastSurvival;
        ServerLevel target = position == null ? null : world(ctx.getSource().getServer(), position.world());
        double x, y, z;
        float yaw, pitch;
        if (target == null) {
            target = world(ctx.getSource().getServer(), config.defaultSurvivalDimension);
            if (target == null) {
                sendMessage(player, config.messages.survivalWorldUnavailable);
                return 0;
            }
            var respawn = target.getRespawnData();
            BlockPos spawn = respawn.pos();
            x = spawn.getX() + .5;
            y = spawn.getY();
            z = spawn.getZ() + .5;
            yaw = respawn.yaw();
            pitch = respawn.pitch();
        } else {
            x = position.x(); y = position.y(); z = position.z();
            yaw = position.yaw(); pitch = position.pitch();
        }
        // Save lobby state before leaving it; target loaded before touching inventory.
        saveOnline(player, ctx.getSource().getServer());
        if (!player.teleportTo(target, x, y, z, Set.of(), yaw, pitch, false)) {
            sendMessage(player, config.messages.survivalTeleportFailed);
            return 0;
        }
        if (config.separateInventories && !switchGroup(player, record, "SURVIVAL", ctx.getSource().getServer())) return 0;
        pendingJoin.remove(player.getUUID());
        sendMessage(player, config.messages.survivalSuccess);
        return 1;
    }
}
