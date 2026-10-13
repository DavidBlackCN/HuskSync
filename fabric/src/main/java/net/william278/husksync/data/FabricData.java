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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lombok.*;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.core.component.DataComponents;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.william278.desertwell.util.ThrowingConsumer;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.config.Settings.SynchronizationSettings.AttributeSettings;
import net.william278.husksync.mixins.HungerManagerMixin;
import net.william278.husksync.user.FabricUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;

import static net.william278.husksync.util.FabricKeyedAdapter.*;

public abstract class FabricData implements Data {

    @Override
    public void apply(@NotNull UserDataHolder user, @NotNull HuskSync plugin) {
        this.apply((FabricUser) user, (FabricHuskSync) plugin);
    }

    protected abstract void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin);

    @Getter
    public static abstract class Items extends FabricData implements Data.Items {

        private final @Nullable ItemStack @NotNull [] contents;

        private Items(@Nullable ItemStack @NotNull [] contents) {
            this.contents = Arrays.stream(contents.clone())
                    .map(i -> i == null || i.isEmpty() ? null : i.copy())
                    .toArray(ItemStack[]::new);
        }

        @Nullable
        @Override
        public Stack @NotNull [] getStack() {
            return Arrays.stream(contents)
                    .map(stack -> stack != null ? new Stack(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            stack.getCount(),
                            stack.getHoverName().getString(),
                            stack.getOrDefault(DataComponents.LORE, net.minecraft.world.item.component.ItemLore.EMPTY).lines().stream()
                                    .map(Component::getString)
                                    .toList(),
                            stack.getEnchantments().keySet().stream()
                                    .flatMap(holder -> holder.unwrapKey().stream())
                                    .map(key -> key.identifier().toString())
                                    .toList()
                    ) : null)
                    .toArray(Stack[]::new);
        }

        @Override
        public void clear() {
            Arrays.fill(contents, null);
        }

        @Override
        public void setContents(@NotNull Data.Items contents) {
            this.setContents(((FabricData.Items) contents).getContents());
        }

        public void setContents(@Nullable ItemStack @NotNull [] contents) {
            // Ensure the array is the correct length for the inventory
            if (contents.length != this.contents.length) {
                contents = Arrays.copyOf(contents, this.contents.length);
            }
            for (int slot = 0; slot < this.contents.length; slot++) {
                this.contents[slot] = contents[slot] == null || contents[slot].isEmpty() ? null : contents[slot].copy();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FabricData.Items items) || contents.length != items.contents.length) {
                return false;
            }
            for (int slot = 0; slot < contents.length; slot++) {
                if (!ItemStack.matches(contents[slot] == null || contents[slot].isEmpty()
                                ? ItemStack.EMPTY : contents[slot],
                        items.contents[slot] == null || items.contents[slot].isEmpty()
                                ? ItemStack.EMPTY : items.contents[slot])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            for (ItemStack item : contents) {
                hash = 31 * hash + (item == null || item.isEmpty() ? 0
                        : 31 * ItemStack.hashItemAndComponents(item) + item.getCount());
            }
            return hash;
        }

        @Setter
        @Getter
        public static class Inventory extends FabricData.Items implements Data.Items.Inventory {

            // Minecraft 26.3 adds BODY and SADDLE after the legacy 41 inventory slots.
            public static final int SLOT_COUNT = 43;

            @Range(from = 0, to = 8)
            private int heldItemSlot;

            public Inventory(@Nullable ItemStack @NotNull [] contents, int heldItemSlot) {
                super(normalize(contents));
                setHeldItemSlot(heldItemSlot);
            }

            @Override
            public void setHeldItemSlot(int heldItemSlot) {
                if (heldItemSlot < 0 || heldItemSlot > 8) {
                    throw new IllegalArgumentException("Invalid selected inventory slot: " + heldItemSlot);
                }
                this.heldItemSlot = heldItemSlot;
            }

            private static ItemStack[] normalize(ItemStack[] contents) {
                if (contents.length != INVENTORY_SLOT_COUNT && contents.length != SLOT_COUNT) {
                    throw new IllegalArgumentException("Invalid inventory size: " + contents.length);
                }
                return Arrays.copyOf(contents, SLOT_COUNT);
            }

            @NotNull
            public static FabricData.Items.Inventory from(@Nullable ItemStack @NotNull [] contents, int heldItemSlot) {
                return new FabricData.Items.Inventory(contents, heldItemSlot);
            }

            @NotNull
            public static FabricData.Items.Inventory from(@NotNull Collection<ItemStack> contents, int heldItemSlot) {
                return from(contents.toArray(ItemStack[]::new), heldItemSlot);
            }

            @NotNull
            public static FabricData.Items.Inventory empty() {
                return new FabricData.Items.Inventory(new ItemStack[SLOT_COUNT], 0);
            }

            @Override
            public int getSlotCount() {
                return getContents().length;
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                final ServerPlayer player = user.getPlayer();
                if (heldItemSlot < 0 || heldItemSlot > 8 || player.getInventory().getContainerSize() != SLOT_COUNT) {
                    throw new IllegalStateException("Invalid inventory layout or selected slot");
                }
                player.inventoryMenu.getCraftSlots().clearContent();
                player.containerMenu.setCarried(ItemStack.EMPTY);
                final ItemStack[] items = getContents();
                for (int slot = 0; slot < items.length; slot++) {
                    player.getInventory().setItem(slot, items[slot] == null ? ItemStack.EMPTY : items[slot].copy());
                }
                player.getInventory().setSelectedSlot(heldItemSlot);
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastFullState();
            }

        }

        public static class EnderChest extends FabricData.Items implements Data.Items.EnderChest {

            private EnderChest(@Nullable ItemStack @NotNull [] contents) {
                super(contents);
                if (contents.length != ENDER_CHEST_SLOT_COUNT) {
                    throw new IllegalArgumentException("Invalid ender chest size: " + contents.length);
                }
            }

            @NotNull
            public static FabricData.Items.EnderChest adapt(@Nullable ItemStack @NotNull [] contents) {
                return new FabricData.Items.EnderChest(contents);
            }

            @NotNull
            public static FabricData.Items.EnderChest adapt(@NotNull Collection<ItemStack> items) {
                return adapt(items.toArray(ItemStack[]::new));
            }

            @NotNull
            public static FabricData.Items.EnderChest empty() {
                return new FabricData.Items.EnderChest(new ItemStack[ENDER_CHEST_SLOT_COUNT]);
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                final ItemStack[] items = getContents();
                for (int slot = 0; slot < user.getPlayer().getEnderChestInventory().getContainerSize(); slot++) {
                    user.getPlayer().getEnderChestInventory().setItem(
                            slot, items[slot] == null ? ItemStack.EMPTY : items[slot].copy()
                    );
                }
            }

        }

        public static class ItemArray extends FabricData.Items implements Data.Items {

            private ItemArray(@Nullable ItemStack @NotNull [] contents) {
                super(contents);
            }

            @NotNull
            public static ItemArray adapt(@NotNull Collection<ItemStack> drops) {
                return new ItemArray(drops.toArray(ItemStack[]::new));
            }

            @NotNull
            public static ItemArray adapt(@Nullable ItemStack @NotNull [] drops) {
                return new ItemArray(drops);
            }

            @Override
            public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
                throw new UnsupportedOperationException("A generic item array cannot be applied to a player");
            }

        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class PotionEffects extends FabricData implements Data.PotionEffects {

        private final Collection<MobEffectInstance> effects;

        @NotNull
        public static FabricData.PotionEffects from(@NotNull Collection<MobEffectInstance> sei) {
            return new FabricData.PotionEffects(sei.stream().map(MobEffectInstance::new).toList());
        }

        @NotNull
        public static FabricData.PotionEffects adapt(@NotNull Collection<Effect> effects) {
            return from(effects.stream()
                    .map(effect -> {
                        final MobEffect type = matchEffectType(effect.type());
                        if (type == null) {
                            throw new IllegalArgumentException("Unknown stored potion effect: " + effect.type());
                        }
                        return new MobEffectInstance(
                                BuiltInRegistries.MOB_EFFECT.wrapAsHolder(type),
                                effect.duration(),
                                effect.amplifier(),
                                effect.isAmbient(),
                                effect.showParticles(),
                                effect.hasIcon()
                        );
                    })
                    .filter(Objects::nonNull)
                    .toList()
            );
        }

        @NotNull
        @SuppressWarnings("unused")
        public static FabricData.PotionEffects empty() {
            return new FabricData.PotionEffects(Lists.newArrayList());
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            player.removeAllEffects();
            getEffects().forEach(effect -> player.addEffect(new MobEffectInstance(effect)));
        }

        @NotNull
        @Override
        @Unmodifiable
        public List<Effect> getActiveEffects() {
            return effects.stream()
                    .map(potionEffect -> {
                        final String key = getEffectId(potionEffect.getEffect().value());
                        return key != null ? new Effect(
                                key,
                                potionEffect.getAmplifier(),
                                potionEffect.getDuration(),
                                potionEffect.isAmbient(),
                                potionEffect.isVisible(),
                                potionEffect.showIcon()
                        ) : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Advancements extends FabricData implements Data.Advancements {

        private List<Advancement> completed;

        @NotNull
        public static FabricData.Advancements adapt(@NotNull ServerPlayer player) {
            final MinecraftServer server = Objects.requireNonNull(player.level().getServer(), "Server is null");
            final List<Advancement> advancements = Lists.newArrayList();
            forEachAdvancementEntry(server, advancementEntry -> {
                final AdvancementProgress advancementProgress = player.getAdvancements().getOrStartProgress(advancementEntry);
                final Map<String, Date> awardedCriteria = Maps.newHashMap();

                advancementProgress.getCompletedCriteria().forEach((criteria) -> awardedCriteria.put(
                        criteria,
                        Date.from(Objects.requireNonNull(advancementProgress.getCriterion(criteria)).getObtained())
                ));

                // Only save the advancement if criteria has been completed
                if (!awardedCriteria.isEmpty()) {
                    advancements.add(Advancement.adapt(
                            advancementEntry.id().toString(),
                            awardedCriteria
                    ));
                }
            });
            return new FabricData.Advancements(advancements);
        }

        @NotNull
        public static FabricData.Advancements from(@NotNull List<Advancement> advancements) {
            return new FabricData.Advancements(advancements);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            final MinecraftServer server = Objects.requireNonNull(player.level().getServer(), "Server is null");
            final PlayerAdvancements tracker = player.getAdvancements();
            final Map<String, Advancement> records = new HashMap<>();
            completed.forEach(advancement -> records.put(advancement.getKey(), advancement));
            final Map<net.minecraft.advancements.AdvancementHolder, AdvancementProgress> restored = new LinkedHashMap<>();
            // Decode every entry before replacing live progress, including empty entries to revoke stale state.
            forEachAdvancementEntry(server, entry -> restored.put(entry, decodeProgress(records.get(entry.id().toString()))));
            ((FabricAdvancementTracker) tracker).husksync$replaceProgress(restored);
            tracker.flushDirty(player, false);
        }

        static AdvancementProgress decodeProgress(@Nullable Advancement advancement) {
            final JsonObject encoded = new JsonObject();
            final JsonObject criteria = new JsonObject();
            if (advancement != null) {
                // The target Minecraft advancement codec uses this same vanilla save-date format.
                final var format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                        .withZone(java.time.ZoneOffset.UTC);
                advancement.getCompletedCriteria().forEach((criterion, date) ->
                        criteria.addProperty(criterion, format.format(date.toInstant())));
            }
            encoded.add("criteria", criteria);
            return AdvancementProgress.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        }

        // Performs a consuming function for every advancement entry registered on the server
        private static void forEachAdvancementEntry(
                @NotNull MinecraftServer server,
                @NotNull ThrowingConsumer<net.minecraft.advancements.AdvancementHolder> con
        ) {
            server.getAdvancements().getAllAdvancements().forEach(con);
        }

    }

    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Location extends FabricData implements Data.Location, Adaptable {
        @SerializedName("x")
        private double x;
        @SerializedName("y")
        private double y;
        @SerializedName("z")
        private double z;
        @SerializedName("yaw")
        private float yaw;
        @SerializedName("pitch")
        private float pitch;
        @SerializedName("world")
        private World world;

        @NotNull
        public static FabricData.Location from(double x, double y, double z,
                                               float yaw, float pitch, @NotNull World world) {
            return new FabricData.Location(x, y, z, yaw, pitch, world);
        }

        @NotNull
        public static FabricData.Location adapt(@NotNull ServerPlayer player) {
            final String worldName = player.level().dimension().identifier().toString();
            final String dimensionType = player.level().dimensionTypeRegistration().unwrapKey()
                    .map(key -> key.identifier().toString()).orElse(worldName);
            return from(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                    new World(worldName, UUID.nameUUIDFromBytes(dimensionType.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            dimensionType));
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            final MinecraftServer server = plugin.getMinecraftServer();

            // Find world
            final String worldName = world.name();
            final ServerLevel target = server.getLevel(server.levelKeys().stream()
                    .filter(key -> key.identifier().equals(Identifier.tryParse(worldName))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Invalid target world: %s".formatted(worldName))));

            // Apply teleport
            try {
                player.stopRiding();
                if (!player.teleportTo(Objects.requireNonNull(target, "Target world is not loaded"),
                        x, y, z, Set.of(), yaw, pitch, true)) {
                    throw new IllegalStateException("Target world rejected teleport");
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to apply location", e);
            }
        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Statistics extends FabricData implements Data.Statistics, Adaptable {

        private static final String BLOCK_STAT_TYPE = "block";
        private static final String ITEM_STAT_TYPE = "item";
        private static final String ENTITY_STAT_TYPE = "entity_type";

        @SerializedName("generic")
        private Map<String, Integer> genericStatistics;
        @SerializedName("blocks")
        private Map<String, Map<String, Integer>> blockStatistics;
        @SerializedName("items")
        private Map<String, Map<String, Integer>> itemStatistics;
        @SerializedName("entities")
        private Map<String, Map<String, Integer>> entityStatistics;

        @NotNull
        public static FabricData.Statistics adapt(@NotNull ServerPlayer player) throws IllegalStateException {
            final Map<String, Map<String, Integer>> blocks = new HashMap<>(),
                    items = new HashMap<>(), entities = new HashMap<>();
            BuiltInRegistries.STAT_TYPE.forEach(type -> {
                final String category = type.getRegistry().key().identifier().getPath();
                final Map<String, Map<String, Integer>> target = switch (category) {
                    case BLOCK_STAT_TYPE -> blocks;
                    case ITEM_STAT_TYPE -> items;
                    case ENTITY_STAT_TYPE -> entities;
                    default -> null;
                };
                if (target == null) {
                    return;
                }
                target.put(Objects.requireNonNull(BuiltInRegistries.STAT_TYPE.getKey(type)).toString(),
                        captureStats(player, type));
            });
            return new FabricData.Statistics(captureStats(player, Stats.CUSTOM), blocks, items, entities);
        }

        private static <T> Map<String, Integer> captureStats(ServerPlayer player, StatType<T> type) {
            final Map<String, Integer> values = new HashMap<>();
            type.getRegistry().forEach(value -> {
                final int count = player.getStats().getValue(type, value);
                if (count != 0) {
                    values.put(Objects.requireNonNull(type.getRegistry().getKey(value)).toString(), count);
                }
            });
            return values;
        }

        @NotNull
        public static FabricData.Statistics from(@NotNull Map<String, Integer> generic,
                                                 @NotNull Map<String, Map<String, Integer>> blocks,
                                                 @NotNull Map<String, Map<String, Integer>> items,
                                                 @NotNull Map<String, Map<String, Integer>> entities) {
            return new FabricData.Statistics(generic, blocks, items, entities);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            final ServerPlayer player = user.getPlayer();
            applyStats(player, Stats.CUSTOM, genericStatistics);
            BuiltInRegistries.STAT_TYPE.forEach(type -> {
                if (type == Stats.CUSTOM) {
                    return;
                }
                final String category = type.getRegistry().key().identifier().getPath();
                final Map<String, Map<String, Integer>> source = switch (category) {
                    case BLOCK_STAT_TYPE -> blockStatistics;
                    case ITEM_STAT_TYPE -> itemStatistics;
                    case ENTITY_STAT_TYPE -> entityStatistics;
                    default -> null;
                };
                if (source != null) {
                    applyStats(player, type, source.getOrDefault(
                            Objects.requireNonNull(BuiltInRegistries.STAT_TYPE.getKey(type)).toString(), Map.of()));
                }
            });
            player.getStats().markAllDirty();
            player.getStats().sendStats(player);
        }

        private static <T> void applyStats(ServerPlayer player, StatType<T> type, Map<String, Integer> values) {
            // Snapshots omit zeroes; reset absent registered statistics as well as applying present values.
            type.getRegistry().forEach(value -> player.getStats().setValue(player, type.get(value),
                    values.getOrDefault(Objects.requireNonNull(type.getRegistry().getKey(value)).toString(), 0)));
        }

    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Attributes extends FabricData implements Data.Attributes, Adaptable {

        private List<Attribute> attributes;

        @NotNull
        public static FabricData.Attributes adapt(@NotNull ServerPlayer player, @NotNull HuskSync plugin) {
            final List<Attribute> attributes = Lists.newArrayList();
            final AttributeSettings settings = plugin.getSettings().getSynchronization().getAttributes();
            BuiltInRegistries.ATTRIBUTE.forEach(id -> {
                final AttributeInstance instance = player.getAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(id));
                final Identifier key = BuiltInRegistries.ATTRIBUTE.getKey(id);
                if (instance == null || key == null || settings.isIgnoredAttribute(key.toString())) {
                    return;
                }
                final Set<Modifier> modifiers = captureModifiers(instance, settings);
                attributes.add(new Attribute(
                        key.toString(),
                        instance.getBaseValue(),
                        modifiers
                ));
            });
            return new FabricData.Attributes(attributes);
        }

        static Set<Modifier> captureModifiers(@NotNull AttributeInstance instance,
                                              @NotNull AttributeSettings settings) {
            final Set<Modifier> modifiers = Sets.newHashSet();
            instance.getModifiers().stream()
                    .filter(modifier -> !settings.isIgnoredModifier(modifier.id().toString()))
                    .forEach(modifier -> modifiers.add(new Modifier(
                        modifier.id().toString(),
                        modifier.amount(),
                        modifier.operation().id(),
                        Modifier.ANY_EQUIPMENT_SLOT_GROUP
                )));
            return modifiers;
        }

        public Optional<Attribute> getAttribute(@NotNull net.minecraft.world.entity.ai.attributes.Attribute id) {
            return Optional.ofNullable(BuiltInRegistries.ATTRIBUTE.getKey(id)).map(Identifier::toString)
                    .flatMap(key -> attributes.stream().filter(attribute -> attribute.name().equals(key)).findFirst());
        }

        @SuppressWarnings("unused")
        public Optional<Attribute> getAttribute(@NotNull String key) {
            final net.minecraft.world.entity.ai.attributes.Attribute attribute = matchAttribute(key);
            if (attribute == null) {
                return Optional.empty();
            }
            return getAttribute(attribute);
        }

        @Override
        protected void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
            final AttributeSettings settings = plugin.getSettings().getSynchronization().getAttributes();
            BuiltInRegistries.ATTRIBUTE.forEach(id -> {
                final Identifier key = BuiltInRegistries.ATTRIBUTE.getKey(id);
                if (key == null || settings.isIgnoredAttribute(key.toString())) {
                    return;
                }
                final var holder = BuiltInRegistries.ATTRIBUTE.wrapAsHolder(id);
                final Attribute attribute = getAttribute(id).orElse(null);
                if (attribute == null) {
                    user.getPlayer().getAttributes().resetBaseValue(holder);
                }
                applyAttribute(user.getPlayer().getAttribute(holder), attribute, settings);
            });

        }

        static void applyAttribute(@Nullable AttributeInstance instance, @Nullable Attribute attribute,
                                   @NotNull AttributeSettings settings) {
            if (instance == null) {
                return;
            }
            // Effect and game-mode modifiers belong to local state and must survive snapshot replacement.
            instance.getModifiers().stream()
                    .filter(modifier -> !settings.isIgnoredModifier(modifier.id().toString()))
                    .toList().forEach(instance::removeModifier);
            if (attribute != null) {
                instance.setBaseValue(attribute.baseValue());
                attribute.modifiers().stream()
                        .filter(modifier -> !settings.isIgnoredModifier(modifier.name()))
                        .forEach(modifier -> instance.addTransientModifier(new AttributeModifier(
                        Identifier.parse(modifier.name()),
                        modifier.amount(),
                        AttributeModifier.Operation.BY_ID.apply(modifier.operation())
                )));
            }
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Health extends FabricData implements Data.Health, Adaptable {
        @SerializedName("health")
        private double health;
        @SerializedName("health_scale")
        private double healthScale;
        @SerializedName("is_health_scaled")
        private boolean isHealthScaled;


        @NotNull
        public static FabricData.Health from(double health, double scale, boolean isScaled) {
            return new FabricData.Health(health, scale, isScaled);
        }

        @NotNull
        public static FabricData.Health adapt(@NotNull ServerPlayer player) {
            return from(
                    player.getHealth(),
                    20.0f, false // Health scale is a Bukkit API feature, not used in Fabric
            );
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            player.setHealth((float) health);
        }

    }


    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Hunger extends FabricData implements Data.Hunger, Adaptable {

        @SerializedName("food_level")
        private int foodLevel;
        @SerializedName("saturation")
        private float saturation;
        @SerializedName("exhaustion")
        private float exhaustion;

        @NotNull
        public static FabricData.Hunger adapt(@NotNull ServerPlayer player) {
            final FoodData hunger = player.getFoodData();
            float exhaustion = ((HungerManagerMixin) hunger).getExhaustion();
            return from(hunger.getFoodLevel(), hunger.getSaturationLevel(), exhaustion);
        }

        @NotNull
        public static FabricData.Hunger from(int foodLevel, float saturation, float exhaustion) {
            return new FabricData.Hunger(foodLevel, saturation, exhaustion);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            final FoodData hunger = player.getFoodData();
            hunger.setFoodLevel(foodLevel);
            hunger.setSaturation(saturation);
            ((HungerManagerMixin) hunger).setExhaustion(exhaustion);
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Experience extends FabricData implements Data.Experience, Adaptable {

        @SerializedName("total_experience")
        private int totalExperience;

        @SerializedName("exp_level")
        private int expLevel;

        @SerializedName("exp_progress")
        private float expProgress;

        @NotNull
        public static FabricData.Experience from(int totalExperience, int expLevel, float expProgress) {
            return new FabricData.Experience(totalExperience, expLevel, expProgress);
        }

        @NotNull
        public static FabricData.Experience adapt(@NotNull ServerPlayer player) {
            return from(player.totalExperience, player.experienceLevel, player.experienceProgress);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            player.totalExperience = totalExperience;
            player.setExperienceLevels(expLevel);
            player.setExperiencePoints((int) (player.getXpNeededForNextLevel() * expProgress));
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class GameMode extends FabricData implements Data.GameMode, Adaptable {

        @SerializedName("game_mode")
        private String gameMode;

        @NotNull
        public static FabricData.GameMode from(@NotNull String gameMode) {
            return new FabricData.GameMode(gameMode);
        }

        @NotNull
        public static FabricData.GameMode adapt(@NotNull ServerPlayer player) {
            return from(player.gameMode.getGameModeForPlayer().getName());
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            user.getPlayer().setGameMode(net.minecraft.world.level.GameType.byName(gameMode));
        }

    }

    @Getter
    @Setter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class FlightStatus extends FabricData implements Data.FlightStatus, Adaptable {

        @SerializedName("allow_flight")
        private boolean allowFlight;
        @SerializedName("is_flying")
        private boolean flying;

        @NotNull
        public static FabricData.FlightStatus from(boolean allowFlight, boolean flying) {
            return new FabricData.FlightStatus(allowFlight, allowFlight && flying);
        }

        @NotNull
        public static FabricData.FlightStatus adapt(@NotNull ServerPlayer player) {
            return from(player.getAbilities().mayfly, player.getAbilities().flying);
        }

        @Override
        public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) throws IllegalStateException {
            final ServerPlayer player = user.getPlayer();
            player.getAbilities().mayfly = allowFlight;
            player.getAbilities().flying = allowFlight && flying;
            player.onUpdateAbilities();
        }

    }

}
