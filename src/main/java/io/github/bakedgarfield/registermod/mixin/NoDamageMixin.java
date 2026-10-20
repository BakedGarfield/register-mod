package io.github.bakedgarfield.registermod.mixin;

import io.github.bakedgarfield.registermod.RegisterMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class NoDamageMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void registermod$noDamage(ServerLevel world, DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!RegisterMod.isAuthenticated((ServerPlayer) (Object) this)) cir.setReturnValue(false);
    }
}
