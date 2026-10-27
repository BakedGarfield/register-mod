package io.github.bakedgarfield.lobbymanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Player-specific inventory and last survival location. Persisted as JSON by PlayerStore. */
public final class PlayerRecord {
    public String activeGroup = "SURVIVAL";
    public Position lastSurvival;
    public InventorySnapshot survival;
    public InventorySnapshot lobby;

    public record Position(String world, double x, double y, double z, float yaw, float pitch) { }

    public static Position location(ServerPlayer player) {
        return new Position(player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    /** JSON codecs retain data components, including enchanted and custom modded items. */
    public static final class InventorySnapshot {
        public JsonArray main = new JsonArray();
        public JsonArray enderChest = new JsonArray();
        public JsonObject equipment = new JsonObject();
        public int selected;
        public int level;
        public int totalXp;
        public float xpProgress;
        public int food = 20;
        public float saturation = 5f;
        public float health = 20f;
        public String gameMode = "survival";

        private static final EquipmentSlot[] ARMOR_AND_HAND = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.BODY, EquipmentSlot.SADDLE
        };

        private static JsonElement encode(RegistryAccess access, ItemStack stack) {
            if (stack.isEmpty()) return com.google.gson.JsonNull.INSTANCE;
            return ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), stack)
                    .getOrThrow();
        }

        private static ItemStack decode(RegistryAccess access, JsonElement element) {
            if (element == null || element.isJsonNull()) return ItemStack.EMPTY;
            return ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, access), element)
                    .getOrThrow();
        }

        public static InventorySnapshot empty(ServerPlayer player) {
            InventorySnapshot snapshot = new InventorySnapshot();
            // The creation of an empty lobby kit never copies the player's survival items.
            for (int i = 0; i < player.getInventory().getNonEquipmentItems().size(); i++) {
                snapshot.main.add(com.google.gson.JsonNull.INSTANCE);
            }
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                snapshot.enderChest.add(com.google.gson.JsonNull.INSTANCE);
            }
            snapshot.gameMode = "adventure";
            return snapshot;
        }

        public static InventorySnapshot capture(ServerPlayer player, RegistryAccess access) {
            InventorySnapshot s = new InventorySnapshot();
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) s.main.add(encode(access, stack));
            for (EquipmentSlot slot : ARMOR_AND_HAND) s.equipment.add(slot.getName(), encode(access, player.getItemBySlot(slot)));
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
                s.enderChest.add(encode(access, player.getEnderChestInventory().getItem(i)));
            }
            s.selected = player.getInventory().getSelectedSlot();
            s.level = player.experienceLevel;
            s.totalXp = player.totalExperience;
            s.xpProgress = player.experienceProgress;
            s.food = player.getFoodData().getFoodLevel();
            s.saturation = player.getFoodData().getSaturationLevel();
            s.health = player.getHealth();
            s.gameMode = player.gameMode().getName();
            return s;
        }

        /** Parse all item data *before* mutating the player, to avoid half-restored inventories. */
        public void restore(ServerPlayer player, RegistryAccess access, boolean lobbyMode) {
            ItemStack[] mainStacks = new ItemStack[player.getInventory().getNonEquipmentItems().size()];
            ItemStack[] endStacks = new ItemStack[player.getEnderChestInventory().getContainerSize()];
            ItemStack[] equipStacks = new ItemStack[ARMOR_AND_HAND.length];
            for (int i = 0; i < mainStacks.length; i++) mainStacks[i] = decode(access, i < main.size() ? main.get(i) : null);
            for (int i = 0; i < endStacks.length; i++) endStacks[i] = decode(access, i < enderChest.size() ? enderChest.get(i) : null);
            for (int i = 0; i < equipStacks.length; i++) {
                EquipmentSlot slot = ARMOR_AND_HAND[i];
                equipStacks[i] = decode(access, equipment.has(slot.getName()) ? equipment.get(slot.getName()) : null);
            }
            player.closeContainer();
            for (int i = 0; i < mainStacks.length; i++) player.getInventory().setItem(i, mainStacks[i]);
            for (int i = 0; i < endStacks.length; i++) player.getEnderChestInventory().setItem(i, endStacks[i]);
            for (int i = 0; i < equipStacks.length; i++) player.setItemSlot(ARMOR_AND_HAND[i], equipStacks[i]);
            player.getInventory().setSelectedSlot(Math.clamp(selected, 0, 8));
            player.experienceLevel = lobbyMode ? 0 : level;
            player.totalExperience = lobbyMode ? 0 : totalXp;
            player.experienceProgress = lobbyMode ? 0f : xpProgress;
            player.getFoodData().setFoodLevel(lobbyMode ? 20 : food);
            player.getFoodData().setSaturation(lobbyMode ? 20f : saturation);
            player.setHealth(lobbyMode ? player.getMaxHealth() : Math.min(player.getMaxHealth(), Math.max(1, health)));
            player.setGameMode(lobbyMode ? GameType.ADVENTURE : switch (gameMode) {
                case "creative" -> GameType.CREATIVE;
                case "spectator" -> GameType.SPECTATOR;
                case "adventure" -> GameType.ADVENTURE;
                default -> GameType.SURVIVAL;
            });
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
    }
}
