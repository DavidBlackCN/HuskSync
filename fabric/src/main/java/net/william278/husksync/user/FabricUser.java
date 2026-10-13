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

package net.william278.husksync.user;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.gui.SimpleGui;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.FabricData;
import net.william278.husksync.data.FabricUserDataHolder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

public class FabricUser extends OnlineUser implements FabricUserDataHolder {

    private final HuskSync plugin;
    private volatile ServerPlayer player;

    private FabricUser(@NotNull ServerPlayer player, @NotNull HuskSync plugin) {
        super(player.getUUID(), player.getName().getString());
        this.player = player;
        this.plugin = plugin;
    }

    @NotNull
    @ApiStatus.Internal
    public static FabricUser adapt(@NotNull ServerPlayer player, @NotNull HuskSync plugin) {
        final FabricUser existing = ((FabricHuskSync) plugin).getPlayerMap().get(player.getUUID());
        return existing != null && existing.getPlayer() == player ? existing : new FabricUser(player, plugin);
    }

    @Override
    public boolean hasDisconnected() {
        return ((FabricHuskSync) plugin).getPlayerMap().get(getUuid()) != this
                || plugin.getDisconnectingPlayers().contains(getUuid()) || player.hasDisconnected();
    }

    @NotNull
    @Override
    public Audience getAudience() {
        return plugin.getAudiences().player(getUuid());
    }

    @Override
    @Deprecated(since = "3.6.7")
    public void sendToast(@NotNull Component title, @NotNull Component description, @NotNull String iconMaterial,
                          @NotNull String backgroundType) {
        plugin.log(Level.WARNING, "Toast notifications are deprecated. " +
                "Please change your notification display slot to CHAT, ACTION_BAR or NONE.");
        this.sendActionBar(title);
    }

    @Override
    public void showGui(@NotNull Data.Items items, @NotNull Component title, boolean editable, int size,
                        @NotNull Consumer<Data.Items> onClose) {
        plugin.runSync(() -> {
            if (!hasDisconnected()) {
                new ItemViewerGui(size, player, title, (FabricData.Items) items, onClose, editable, plugin).open();
            }
        }, this);
    }

    private static class ItemViewerGui extends SimpleGui {

        private final Consumer<Data.Items> onClose;
        private final SimpleContainer contents;
        private final boolean editable;
        private boolean saved;

        private ItemViewerGui(int size, @NotNull ServerPlayer player, @NotNull Component title,
                              @NotNull FabricData.Items items, @NotNull Consumer<Data.Items> onClose,
                              boolean editable, @NotNull HuskSync plugin) {
            super(getMenuType(size), player, false);
            this.onClose = onClose;
            this.editable = editable;
            this.contents = new SimpleContainer(size);
            setTitle(((MinecraftServerAudiences) plugin.getAudiences()).asNative(title));
            setLockPlayerInventory(!editable);
            final ItemStack[] source = items.getContents();
            for (int slot = 0; slot < size; slot++) {
                contents.setItem(slot, slot < source.length && source[slot] != null
                        ? source[slot].copy() : ItemStack.EMPTY);
                // Real backing slots allow SGUI 2 to preserve edits, hotbar swaps, and shift-clicks.
                // The remaining cells in the final GUI row are left as non-interactive virtual slots.
                setSlot(slot, new Slot(contents, slot, 0, 0) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return editable;
                    }

                    @Override
                    public boolean mayPickup(Player viewer) {
                        return editable;
                    }
                });
            }
        }

        @Override
        public void onRemoved() {
            if (saved) {
                return;
            }
            saved = true;
            final ItemStack[] result = new ItemStack[contents.getContainerSize()];
            for (int slot = 0; slot < result.length; slot++) {
                result[slot] = contents.getItem(slot).copy();
            }
            onClose.accept(FabricData.Items.ItemArray.adapt(result));
        }

        @Override
        public boolean onAnyClick(int index, @NotNull ClickType type, @NotNull ContainerInput action) {
            return editable;
        }

        @Override
        public boolean onClick(int index, @NotNull ClickType type, @NotNull ContainerInput action,
                               @NotNull GuiElement element) {
            return false;
        }

        @NotNull
        private static MenuType<?> getMenuType(int size) {
            return switch ((size + 8) / 9) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                case 6 -> MenuType.GENERIC_9x6;
                default -> throw new IllegalArgumentException("Unsupported inventory GUI size: " + size);
            };
        }
    }

    @Override
    public boolean hasPermission(@NotNull String node) {
        final boolean requiresOp = Boolean.TRUE.equals(
                ((FabricHuskSync) plugin).getPermissions().getOrDefault(node, true)
        );
        return requiresOp ? Permissions.check(player, node, 3) : Permissions.check(player, node, true);
    }

    @Override
    public boolean isDead() {
        return player.getHealth() <= 0.0f;
    }

    @Override
    public boolean isLocked() {
        return plugin.isLocked(getUuid());
    }

    @Override
    public boolean isNpc() {
        return false;
    }

    @NotNull
    @Override
    public ServerPlayer getPlayer() {
        return player;
    }

    @Override
    public void setPlayer(@NotNull ServerPlayer player) {
        if (!getUuid().equals(player.getUUID())) {
            throw new IllegalArgumentException("Cannot replace a user with a different player UUID");
        }
        this.player = Objects.requireNonNull(player, "player");
    }

    @NotNull
    @Override
    @ApiStatus.Internal
    public HuskSync getPlugin() {
        return plugin;
    }
}
