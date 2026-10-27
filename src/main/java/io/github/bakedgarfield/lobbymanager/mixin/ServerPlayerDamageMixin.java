package io.github.bakedgarfield.lobbymanager.mixin;

import io.github.bakedgarfield.lobbymanager.LobbyManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents direct damage to players in the lobby, without affecting survival worlds. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDamageMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void lobbymanager$blockLobbyDamage(ServerLevel level, DamageSource source, float amount,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (LobbyManager.noLobbyDamage((ServerPlayer) (Object) this)) cir.setReturnValue(false);
    }
}
