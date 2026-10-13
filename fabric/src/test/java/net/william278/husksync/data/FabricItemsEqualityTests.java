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

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FabricItemsEqualityTests {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Since 26.3, item defaults are bound during resource loading, after registry bootstrap.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void unchangedGuiCopiesCompareEqualAcrossItemContainers() {
        final ItemStack[] contents = new ItemStack[43];
        contents[0] = new ItemStack(Items.STONE, 3);
        contents[0].set(DataComponents.CUSTOM_NAME, Component.literal("Original"));
        final var inventory = FabricData.Items.Inventory.from(contents, 2);
        final var displayed = FabricData.Items.ItemArray.adapt(inventory.getContents());
        final var closed = FabricData.Items.ItemArray.adapt(displayed.getContents());

        assertNotSame(contents[0], inventory.getContents()[0]);
        assertNotSame(inventory.getContents()[0], closed.getContents()[0]);
        assertEquals(inventory, closed, "Closing an unedited GUI must not publish a stale snapshot");
        assertEquals(closed, inventory);
        assertEquals(inventory.hashCode(), closed.hashCode());
    }

    @Test
    void detectsCountAndComponentChanges() {
        final var original = FabricData.Items.ItemArray.adapt(new ItemStack[]{new ItemStack(Items.STONE, 3)});
        final var edited = FabricData.Items.ItemArray.adapt(original.getContents());
        edited.getContents()[0].setCount(2);
        assertNotEquals(original, edited);

        edited.getContents()[0].setCount(3);
        assertEquals(original, edited);
        edited.getContents()[0].set(DataComponents.CUSTOM_NAME, Component.literal("Edited"));
        assertNotEquals(original, edited);
        assertNull(original.getContents()[0].get(DataComponents.CUSTOM_NAME));
    }

    @Test
    void treatsEveryEmptyRepresentationEquallyAndRespectsSlotCount() {
        final var absent = FabricData.Items.ItemArray.adapt(new ItemStack[2]);
        final var empty = FabricData.Items.ItemArray.adapt(new ItemStack[2]);
        empty.getContents()[0] = ItemStack.EMPTY;
        empty.getContents()[1] = new ItemStack(Items.STONE, 0);
        assertEquals(absent, empty);
        assertEquals(absent.hashCode(), empty.hashCode());
        assertNotEquals(absent, FabricData.Items.ItemArray.adapt(new ItemStack[1]));
    }
}
