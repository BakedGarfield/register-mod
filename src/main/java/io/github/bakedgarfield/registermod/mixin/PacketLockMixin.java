package io.github.bakedgarfield.registermod.mixin;

import io.github.bakedgarfield.registermod.RegisterMod;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Before authentication, block server-bound gameplay/chat/commands.
 * This is a beta restriction layer, NOT independently audited anti-impersonation protection.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketLockMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void registermod$commands(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (!RegisterMod.allowedCommand(player, packet.command())) ci.cancel();
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void registermod$signedCommands(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (!RegisterMod.allowedCommand(player, packet.command())) ci.cancel();
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void registermod$chat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (!RegisterMod.isAuthenticated(player)) ci.cancel();
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void registermod$movement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!RegisterMod.isAuthenticated(player)) ci.cancel();
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void registermod$vehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!RegisterMod.isAuthenticated(player)) ci.cancel();
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void registermod$playerActions(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!RegisterMod.isAuthenticated(player)) ci.cancel();
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void registermod$inventory(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (!RegisterMod.isAuthenticated(player)) ci.cancel();
    }
}
