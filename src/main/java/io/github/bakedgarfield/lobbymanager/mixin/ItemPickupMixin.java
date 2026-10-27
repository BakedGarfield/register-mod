package io.github.bakedgarfield.lobbymanager.mixin;

import io.github.bakedgarfield.lobbymanager.LobbyManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lobbymanager$noPickup(Player player, CallbackInfo ci) {
        if (LobbyManager.noLobbyPickup(player)) ci.cancel();
    }
}
