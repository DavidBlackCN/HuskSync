/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.mixins;

import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.william278.husksync.event.InventoryClickCallback;
import net.william278.husksync.event.ItemDropCallback;
import net.william278.husksync.event.PlayerCommandCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Adapted from simplerauth (https://github.com/lolicode-org/simplerauth), which is licensed under the MIT License
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayer player;

    // Run after vanilla's thread handoff, before any inventory mutation. Menu synchronization corrects
    // all predicted slots and the actual carried stack, including outside-window clicks and creative drops.
    @Inject(method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER), cancellable = true)
    private void onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        final ItemStack stack = player.getMainHandItem();
        final InteractionResult result = switch (packet.getAction()) {
            case DROP_ITEM, DROP_ALL_ITEMS -> ItemDropCallback.EVENT.invoker().interact(player, stack);
            case SWAP_ITEM_WITH_OFFHAND -> InventoryClickCallback.EVENT.invoker().interact(player, stack);
            default -> InteractionResult.PASS;
        };
        if (result == InteractionResult.FAIL) {
            ci.cancel();
            husksync$resendInventory();
        }
    }

    @Inject(method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER), cancellable = true)
    private void onInventoryClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        final AbstractContainerMenu menu = player.containerMenu;
        final int slot = packet.slotNum();
        final ItemStack stack = slot >= 0 && slot < menu.slots.size()
                ? menu.getSlot(slot).getItem() : menu.getCarried();
        if (InventoryClickCallback.EVENT.invoker().interact(player, stack) == InteractionResult.FAIL) {
            ci.cancel();
            husksync$resendInventory();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot(Lnet/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER), cancellable = true)
    private void onCreativeInventoryClick(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        final InteractionResult result = packet.slotNum() < 0
                ? ItemDropCallback.EVENT.invoker().interact(player, packet.itemStack())
                : InventoryClickCallback.EVENT.invoker().interact(player, packet.itemStack());
        if (result == InteractionResult.FAIL) {
            ci.cancel();
            husksync$resendInventory();
        }
    }

    @Unique
    private void husksync$resendInventory() {
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.sendAllDataToRemote();
        }
    }

    // Both methods run on the server thread. For signed commands, preserve signature-chain decoding
    // before cancelling execution so that the player's next signed message remains valid.
    @Inject(method = "performUnsignedChatCommand(Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"),
            cancellable = true)
    private void onUnsignedCommand(String command, CallbackInfo ci) {
        if (PlayerCommandCallback.EVENT.invoker().interact(player, command) == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(method = "performSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V"),
            cancellable = true)
    private void onSignedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeenMessages,
                                 CallbackInfo ci) {
        if (PlayerCommandCallback.EVENT.invoker().interact(player, packet.command()) == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

}
