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

package net.william278.husksync.data;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

import static net.william278.husksync.config.Settings.SynchronizationSettings.SaveOnDeathSettings;

public interface FabricUserDataHolder extends UserDataHolder {

    @Override
    default boolean requiresSnapshotOnEventThread() {
        return true;
    }

    @Override
    default Optional<? extends Data> getData(@NotNull Identifier id) {
        if (id.isCustom()) {
            return Optional.ofNullable(getCustomDataStore().get(id));
        }

        try {
            return switch (id.getKeyValue()) {
                case "inventory" -> getInventory();
                case "ender_chest" -> getEnderChest();
                case "potion_effects" -> getPotionEffects();
                case "advancements" -> getAdvancements();
                case "location" -> getLocation();
                case "statistics" -> getStatistics();
                case "health" -> getHealth();
                case "hunger" -> getHunger();
                case "attributes" -> getAttributes();
                case "experience" -> getExperience();
                case "game_mode" -> getGameMode();
                case "flight_status" -> getFlightStatus();
                case "persistent_data" -> getPersistentData();
                default -> throw new IllegalStateException(String.format("Unexpected data type: %s", id));
            };
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to capture data for key: " + id.asMinimalString(), e);
        }
    }

    @Override
    default void setData(@NotNull Identifier id, @NotNull Data data) {
        if (id.isCustom()) {
            getCustomDataStore().put(id, data);
        }
        UserDataHolder.super.setData(id, data);
    }

    @NotNull
    @Override
    default Optional<Data.Items.Inventory> getInventory() {
        final SaveOnDeathSettings death = getPlugin().getSettings().getSynchronization().getSaveOnDeath();
        if ((isDead() && !death.isSyncDeadPlayersChangingServer())) {
            return Optional.of(FabricData.Items.Inventory.empty());
        }
        final Inventory inventory = getPlayer().getInventory();
        return Optional.of(FabricData.Items.Inventory.from(
                getCombinedInventory(inventory),
                inventory.getSelectedSlot()
        ));
    }

    // Gets the player's combined inventory; their inventory, plus offhand and armor.
    @Nullable
    private ItemStack @NotNull [] getCombinedInventory(@NotNull Inventory inv) {
        final ItemStack[] combined = new ItemStack[inv.getContainerSize()];
        for (int slot = 0; slot < combined.length; slot++) {
            combined[slot] = inv.getItem(slot).copy();
        }
        return combined;
    }

    @NotNull
    @Override
    default Optional<Data.Items.EnderChest> getEnderChest() {
        final var inventory = getPlayer().getEnderChestInventory();
        final ItemStack[] contents = new ItemStack[inventory.getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = inventory.getItem(slot).copy();
        }
        return Optional.of(FabricData.Items.EnderChest.adapt(contents));
    }

    @NotNull
    @Override
    default Optional<Data.PotionEffects> getPotionEffects() {
        return Optional.of(FabricData.PotionEffects.from(getPlayer().getActiveEffectsMap().values()));
    }

    @NotNull
    @Override
    default Optional<Data.Advancements> getAdvancements() {
        return Optional.of(FabricData.Advancements.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.Location> getLocation() {
        return Optional.of(FabricData.Location.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.Statistics> getStatistics() {
        return Optional.of(FabricData.Statistics.adapt(getPlayer()));
    }

    @Override
    @NotNull
    default Optional<Data.Attributes> getAttributes() {
        return Optional.of(FabricData.Attributes.adapt(getPlayer(), getPlugin()));
    }

    @NotNull
    @Override
    default Optional<Data.Health> getHealth() {
        return Optional.of(FabricData.Health.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.Hunger> getHunger() {
        return Optional.of(FabricData.Hunger.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.Experience> getExperience() {
        return Optional.of(FabricData.Experience.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.GameMode> getGameMode() {
        return Optional.of(FabricData.GameMode.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.FlightStatus> getFlightStatus() {
        return Optional.of(FabricData.FlightStatus.adapt(getPlayer()));
    }

    @NotNull
    @Override
    default Optional<Data.PersistentData> getPersistentData() {
        return Optional.empty(); // Not implemented on Fabric, but maybe we'll do data keys or something
    }

    boolean isDead();

    @NotNull
    ServerPlayer getPlayer();

    void setPlayer(@NotNull ServerPlayer player);

    @Override
    default void respawn() {
        final var plugin = (net.william278.husksync.FabricHuskSync) getPlugin();
        setPlayer(plugin.getMinecraftServer().getPlayerList().respawn(
                getPlayer(), false, net.minecraft.world.entity.Entity.RemovalReason.KILLED
        ));
    }

    @NotNull
    @Override
    Map<Identifier, Data> getCustomDataStore();

}
