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

import de.exlll.configlib.YamlConfigurations;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.william278.husksync.config.Settings.SynchronizationSettings.AttributeSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FabricAttributeTests {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void excludesLocalModifiersFromCapturedAttributes() {
        final var instance = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> { });
        instance.addTransientModifier(modifier("minecraft:effect.health_boost", 4));
        instance.addTransientModifier(modifier("minecraft:creative_mode_test", 2));
        instance.addTransientModifier(modifier("husksync:custom", 6));

        final var captured = FabricData.Attributes.captureModifiers(instance, settings());
        assertEquals(Set.of("husksync:custom"), captured.stream()
                .map(Data.Attributes.Modifier::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void preservesLocalModifiersAndIgnoresTheirValuesInOlderSnapshots() {
        final var instance = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> { });
        instance.addTransientModifier(modifier("minecraft:effect.health_boost", 4));
        instance.addTransientModifier(modifier("husksync:stale", 6));
        final var snapshot = new Data.Attributes.Attribute("minecraft:max_health", 30, Set.of(
                storedModifier("minecraft:effect.health_boost", 100),
                storedModifier("minecraft:creative_mode_test", 100),
                storedModifier("husksync:synced", 2)
        ));

        FabricData.Attributes.applyAttribute(instance, snapshot, settings());

        assertEquals(30, instance.getBaseValue());
        assertEquals(4, instance.getModifier(Identifier.parse("minecraft:effect.health_boost")).amount());
        assertFalse(instance.hasModifier(Identifier.parse("minecraft:creative_mode_test")));
        assertFalse(instance.hasModifier(Identifier.parse("husksync:stale")));
        assertEquals(2, instance.getModifier(Identifier.parse("husksync:synced")).amount());
    }

    @Test
    void absentSnapshotAttributeStillPreservesLocalModifiers() {
        final var instance = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> { });
        instance.addTransientModifier(modifier("minecraft:effect.health_boost", 4));
        instance.addTransientModifier(modifier("husksync:stale", 6));

        FabricData.Attributes.applyAttribute(instance, null, settings());

        assertTrue(instance.hasModifier(Identifier.parse("minecraft:effect.health_boost")));
        assertFalse(instance.hasModifier(Identifier.parse("husksync:stale")));
    }

    private static AttributeModifier modifier(String id, double amount) {
        return new AttributeModifier(Identifier.parse(id), amount, AttributeModifier.Operation.ADD_VALUE);
    }

    private static Data.Attributes.Modifier storedModifier(String id, double amount) {
        return new Data.Attributes.Modifier(id, amount, 0, Data.Attributes.Modifier.ANY_EQUIPMENT_SLOT_GROUP);
    }

    private static AttributeSettings settings() {
        return YamlConfigurations.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                AttributeSettings.class);
    }
}
