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

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.gamerules.GameRules;
import net.william278.husksync.event.PlayerDeathDropsCallback;
import net.william278.husksync.event.ItemDropCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public class PlayerEntityMixin {

    @Inject(method = "dropEquipment(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"), cancellable = true)
    private void onDeathDrops(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) {
            return;
        }
        final Inventory inventory = player.getInventory();
        final boolean keepInventory = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        if (ItemDropCallback.EVENT.invoker().interact(player, ItemStack.EMPTY) == InteractionResult.FAIL) {
            // Match normal death cleanup while preventing stale local inventory from reaching the world.
            // clearContent also clears equipment, including vanishing items; keepInventory keeps everything.
            if (!keepInventory) {
                inventory.clearContent();
            }
            ci.cancel();
            return;
        }
        final ItemStack[] toKeep = new ItemStack[inventory.getContainerSize()];
        final ItemStack[] toDrop = new ItemStack[inventory.getContainerSize()];
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (keepInventory) {
                toKeep[slot] = stack.copy();
            } else if (!EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                toDrop[slot] = stack.copy();
            }
        }
        PlayerDeathDropsCallback.EVENT.invoker().drops(player, toKeep, toDrop);
    }

}
