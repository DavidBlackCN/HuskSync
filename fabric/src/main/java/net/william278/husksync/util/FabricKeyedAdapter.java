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

package net.william278.husksync.util;

import net.minecraft.world.entity.EntityType;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Utility class for adapting "Keyed" Minecraft objects
public final class FabricKeyedAdapter {

    @Nullable
    public static EntityType<?> matchEntityType(@NotNull String key) {
        return getRegistryValue(BuiltInRegistries.ENTITY_TYPE, key);
    }

    @Nullable
    public static String getEntityTypeId(@NotNull EntityType<?> entityType) {
        return getRegistryKey(BuiltInRegistries.ENTITY_TYPE, entityType);
    }

    @Nullable
    public static net.minecraft.world.entity.ai.attributes.Attribute matchAttribute(@NotNull String key) {
        return getRegistryValue(BuiltInRegistries.ATTRIBUTE, key);
    }

    @Nullable
    public static String getAttributeId(@NotNull net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return getRegistryKey(BuiltInRegistries.ATTRIBUTE, attribute);
    }

    @Nullable
    public static MobEffect matchEffectType(@NotNull String key) {
        return getRegistryValue(BuiltInRegistries.MOB_EFFECT, key);
    }

    @Nullable
    public static String getEffectId(@NotNull MobEffect effect) {
        return getRegistryKey(BuiltInRegistries.MOB_EFFECT, effect);
    }

    @Nullable
    private static <T> T getRegistryValue(@NotNull Registry<T> registry, @NotNull String keyString) {
        final Identifier key = Identifier.tryParse(keyString);
        return key != null && registry.containsKey(key) ? registry.getValue(key) : null;
    }

    @Nullable
    private static <T> String getRegistryKey(@NotNull Registry<T> registry, @NotNull T value) {
        final Identifier key = registry.getKey(value);
        return key != null ? key.toString() : null;
    }

}
