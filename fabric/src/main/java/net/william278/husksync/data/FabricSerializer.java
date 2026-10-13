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

import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.Dynamic;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.*;
import net.william278.desertwell.util.Version;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.api.HuskSyncAPI;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static net.william278.husksync.data.Data.Items.Inventory.*;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public abstract class FabricSerializer {

    @ApiStatus.Internal
    protected final HuskSync plugin;

    @SuppressWarnings("unused")
    public FabricSerializer(@NotNull HuskSyncAPI api) {
        this.plugin = api.getPlugin();
    }

    @ApiStatus.Internal
    @NotNull
    public HuskSync getPlugin() {
        return plugin;
    }

    public static class Inventory extends FabricSerializer implements Serializer<FabricData.Items.Inventory>,
            ItemDeserializer {

        public Inventory(@NotNull HuskSync plugin) {
            super(plugin);
        }

        @Override
        public FabricData.Items.Inventory deserialize(@NotNull String serialized, @NotNull Version dataMcVersion)
                throws DeserializationException {
            // Read item NBT from string
            final FabricHuskSync plugin = (FabricHuskSync) getPlugin();
            final CompoundTag root;
            try {
                root = TagParser.parseCompoundFully(serialized);
            } catch (Exception e) {
                throw new DeserializationException("Failed to read item NBT from string (%s)".formatted(serialized), e);
            }

            // Deserialize the inventory data
            final CompoundTag items = root.getCompound(ITEMS_TAG)
                    .orElseThrow(() -> new IllegalArgumentException("Missing inventory items compound"));
            return FabricData.Items.Inventory.from(
                getItems(items, dataMcVersion, plugin),
                root.getIntOr(HELD_ITEM_SLOT_TAG, 0)
            );
        }

        @Override
        public FabricData.Items.Inventory deserialize(@NotNull String serialized) {
            return deserialize(serialized, plugin.getMinecraftVersion());
        }

        @NotNull
        @Override
        public String serialize(@NotNull FabricData.Items.Inventory data) throws SerializationException {
            try {
                final CompoundTag root = new CompoundTag();
                root.put(ITEMS_TAG, serializeItemArray(data.getContents(), (FabricHuskSync) getPlugin()));
                root.putInt(HELD_ITEM_SLOT_TAG, data.getHeldItemSlot());
                return root.toString();
            } catch (Exception e) {
                throw new SerializationException("Failed to serialize inventory item NBT to string", e);
            }
        }

    }

    public static class EnderChest extends FabricSerializer implements Serializer<FabricData.Items.EnderChest>,
            ItemDeserializer {

        public EnderChest(@NotNull HuskSync plugin) {
            super(plugin);
        }

        @Override
        public FabricData.Items.EnderChest deserialize(@NotNull String serialized, @NotNull Version dataMcVersion)
                throws DeserializationException {
            final FabricHuskSync plugin = (FabricHuskSync) getPlugin();
            try {
                final CompoundTag items = TagParser.parseCompoundFully(serialized);
                return FabricData.Items.EnderChest.adapt(getItems(items, dataMcVersion, plugin));
            } catch (Exception e) {
                throw new DeserializationException("Failed to read item NBT from string (%s)".formatted(serialized), e);
            }
        }

        @Override
        public FabricData.Items.EnderChest deserialize(@NotNull String serialized) {
            return deserialize(serialized, plugin.getMinecraftVersion());
        }

        @NotNull
        @Override
        public String serialize(@NotNull FabricData.Items.EnderChest data) throws SerializationException {
            try {
                return serializeItemArray(data.getContents(), (FabricHuskSync) getPlugin()).toString();
            } catch (Exception e) {
                throw new SerializationException("Failed to serialize ender chest item NBT to string", e);
            }
        }
    }

    private interface ItemDeserializer {

        @NotNull
        default ItemStack[] getItems(@NotNull CompoundTag tag, @NotNull Version mcVersion,
                                    @NotNull FabricHuskSync plugin) {
            try {
                final int size = tag.getIntOr("size", -1);
                if (size != Data.Items.Inventory.INVENTORY_SLOT_COUNT
                        && size != FabricData.Items.Inventory.SLOT_COUNT
                        && size != Data.Items.EnderChest.ENDER_CHEST_SLOT_COUNT) {
                    throw new IllegalArgumentException("Invalid stored item array size: " + size);
                }
                final ItemStack[] contents = new ItemStack[size];
                final boolean[] occupied = new boolean[size];
                final ListTag list = tag.getList("items")
                        .orElseThrow(() -> new IllegalArgumentException("Missing item list"));
                final var ops = plugin.getMinecraftServer().registryAccess().createSerializationContext(NbtOps.INSTANCE);
                for (Tag element : list) {
                    if (!(element instanceof CompoundTag entry)) {
                        throw new IllegalArgumentException("Item entry is not a compound");
                    }
                    final int slot = entry.getIntOr("Slot", -1);
                    if (slot < 0 || slot >= size || occupied[slot]) {
                        throw new IllegalArgumentException("Invalid or duplicate item slot: " + slot);
                    }
                    occupied[slot] = true;
                    final Tag item = mcVersion.compareTo(plugin.getMinecraftVersion()) < 0
                            ? plugin.getMinecraftServer().getFixerUpper().update(
                                    References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, entry),
                                    plugin.getDataVersion(mcVersion), plugin.getDataVersion(plugin.getMinecraftVersion())
                            ).getValue() : entry;
                    contents[slot] = ItemStack.CODEC.parse(ops, item).getOrThrow();
                }
                return contents;
            } catch (RuntimeException e) {
                throw new Serializer.DeserializationException("Failed to deserialize stored item data", e);
            }
        }

        @NotNull
        default CompoundTag serializeItemArray(@Nullable ItemStack @NotNull [] items,
                                                @NotNull FabricHuskSync plugin) {
            final CompoundTag container = new CompoundTag();
            container.putInt("size", items.length);
            final ListTag list = new ListTag();
            final var ops = plugin.getMinecraftServer().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            for (int slot = 0; slot < items.length; slot++) {
                final ItemStack item = items[slot];
                if (item == null || item.isEmpty()) {
                    continue;
                }
                // Never save a partial inventory when a registry/component cannot be encoded.
                final Tag encoded = ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
                if (!(encoded instanceof CompoundTag entry)) {
                    throw new IllegalStateException("Item codec did not encode a compound at slot " + slot);
                }
                entry.putInt("Slot", slot);
                list.add(entry);
            }
            container.put(ITEMS_TAG, list);
            return container;
        }

    }

    public static class PotionEffects extends FabricSerializer implements Serializer<FabricData.PotionEffects> {

        private static final TypeToken<List<Data.PotionEffects.Effect>> TYPE = new TypeToken<>() {
        };

        public PotionEffects(@NotNull HuskSync plugin) {
            super(plugin);
        }

        @Override
        public FabricData.PotionEffects deserialize(@NotNull String serialized) throws DeserializationException {
            return FabricData.PotionEffects.adapt(
                    plugin.getGson().fromJson(serialized, TYPE.getType())
            );
        }

        @NotNull
        @Override
        public String serialize(@NotNull FabricData.PotionEffects element) throws SerializationException {
            return plugin.getGson().toJson(element.getActiveEffects());
        }

    }

    public static class Advancements extends FabricSerializer implements Serializer<FabricData.Advancements> {

        private static final TypeToken<List<Data.Advancements.Advancement>> TYPE = new TypeToken<>() {
        };

        public Advancements(@NotNull HuskSync plugin) {
            super(plugin);
        }

        @Override
        public FabricData.Advancements deserialize(@NotNull String serialized) throws DeserializationException {
            return FabricData.Advancements.from(
                    plugin.getGson().fromJson(serialized, TYPE.getType())
            );
        }

        @NotNull
        @Override
        public String serialize(@NotNull FabricData.Advancements element) throws SerializationException {
            return plugin.getGson().toJson(element.getCompleted());
        }
    }

}
