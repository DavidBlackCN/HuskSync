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

package net.william278.husksync.listener;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.config.Settings.SynchronizationSettings.SaveOnDeathSettings;
import net.william278.husksync.data.FabricData;
import net.william278.husksync.event.*;
import net.william278.husksync.user.FabricUser;
import net.william278.husksync.user.OnlineUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FabricEventListener extends EventListener implements LockedHandler {

    public FabricEventListener(@NotNull HuskSync plugin) {
        super(plugin);
        this.registerEvents();
    }

    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register(this::handlePlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(this::handlePlayerQuit);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                ((FabricHuskSync) plugin).getPlayerMap().computeIfPresent(newPlayer.getUUID(), (uuid, user) -> {
                    user.setPlayer(newPlayer);
                    return user;
                }));
        WorldSaveCallback.EVENT.register(this::handleWorldSave);
        PlayerDeathDropsCallback.EVENT.register(this::handlePlayerDeathDrops);

        // Locked events handling
        ItemPickupCallback.EVENT.register(this::handleItemPickup);
        ItemDropCallback.EVENT.register(this::handleItemDrop);
        UseBlockCallback.EVENT.register(this::handleBlockInteract);
        UseEntityCallback.EVENT.register(this::handleEntityInteract);
        UseItemCallback.EVENT.register(this::handleItemInteract);
        PlayerBlockBreakEvents.BEFORE.register(this::handleBlockBreak);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::handleEntityDamage);
        InventoryClickCallback.EVENT.register(this::handleInventoryClick);
        PlayerCommandCallback.EVENT.register(this::handlePlayerCommand);
    }

    private void handlePlayerJoin(@NotNull ServerGamePacketListenerImpl handler, @NotNull PacketSender sender,
                                  @NotNull MinecraftServer server) {
        final FabricUser user = FabricUser.adapt(handler.player, plugin);
        ((FabricHuskSync) plugin).getPlayerMap().put(handler.player.getUUID(), user);
        handlePlayerJoin(user);
    }

    private void handlePlayerQuit(@NotNull ServerGamePacketListenerImpl handler, @NotNull MinecraftServer server) {
        ((FabricHuskSync) plugin).getPlayerMap().remove(handler.player.getUUID());
        final OnlineUser user = FabricUser.adapt(handler.player, plugin);
        handlePlayerQuit(user); // Mark disconnecting, lock, mark for saving
        saveOnPlayerQuit(user); // Take snapshot and save, if marked above
    }

    private void handleWorldSave(@NotNull ServerLevel world) {
        this.saveOnWorldSave(
                world.players().stream().map(player -> (OnlineUser) FabricUser.adapt(player, plugin)).toList()
        );
    }

    private void handlePlayerDeathDrops(@NotNull ServerPlayer player, @Nullable ItemStack @NotNull [] toKeep,
                                        @Nullable ItemStack @NotNull [] toDrop) {
        final SaveOnDeathSettings settings = plugin.getSettings().getSynchronization().getSaveOnDeath();
        this.saveOnPlayerDeath(
                FabricUser.adapt(player, plugin),
                FabricData.Items.ItemArray.adapt(
                        settings.getItemsToSave() == SaveOnDeathSettings.DeathItemsMode.DROPS ? toDrop : toKeep
                )
        );
    }

    private InteractionResult handleItemPickup(Player player, ItemStack itemStack) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private InteractionResult handleItemDrop(Player player, ItemStack itemStack) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private InteractionResult handleBlockInteract(Player player, Level world, InteractionHand hand, BlockHitResult blockHitResult) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private InteractionResult handleEntityInteract(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult entityHitResult) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private InteractionResult handleItemInteract(Player player, Level world, InteractionHand hand) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }


    private boolean handleBlockBreak(Level world, Player player, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
        return !cancelPlayerEvent(player.getUUID());
    }

    private boolean handleEntityDamage(LivingEntity livingEntity, DamageSource damageSource, float v) {
        if (livingEntity instanceof ServerPlayer player) {
            return !cancelPlayerEvent(player.getUUID());
        }
        return true;
    }

    private InteractionResult handleInventoryClick(Player player, ItemStack itemStack) {
        return (cancelPlayerEvent(player.getUUID())) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    private InteractionResult handlePlayerCommand(Player player, String command) {
        final String label = command.split(" ", 2)[0];
        return isCommandDisabled(label) && cancelPlayerEvent(player.getUUID())
                ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    @Override
    @NotNull
    public HuskSync getPlugin() {
        return plugin;
    }
}
