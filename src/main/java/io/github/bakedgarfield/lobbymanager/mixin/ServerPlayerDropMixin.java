package io.github.bakedgarfield.lobbymanager.mixin;

import io.github.bakedgarfield.lobbymanager.LobbyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void lobbymanager$noDrop(ItemStack stack, boolean fromHand, Prediction prediction,
                                 CallbackInfoReturnable<ItemEntity> cir) {
        if (LobbyManager.noLobbyDrop((ServerPlayer)(Object)this)) cir.setReturnValue(null);
    }
}
