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

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FabricInventoryTests {

    @Test
    void upgradesLegacyInventoryWithoutChangingSelectedSlot() {
        final var inventory = FabricData.Items.Inventory.from(new ItemStack[41], 8);
        assertEquals(43, inventory.getSlotCount());
        assertEquals(8, inventory.getHeldItemSlot());
        assertNull(inventory.getContents()[41]);
        assertNull(inventory.getContents()[42]);
    }

    @Test
    void emptyAndCurrentInventoriesUseTheCompleteLayout() {
        assertEquals(43, FabricData.Items.Inventory.empty().getSlotCount());
        assertEquals(43, FabricData.Items.Inventory.from(new ItemStack[43], 0).getSlotCount());
        assertEquals(41, Data.Items.Inventory.INVENTORY_SLOT_COUNT,
                "The shared legacy format must remain unchanged");
    }

    @Test
    void rejectsUnknownLayoutsInsteadOfTruncatingStoredItems() {
        for (int size : new int[]{0, 27, 40, 42, 44, 54}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FabricData.Items.Inventory.from(new ItemStack[size], 0));
        }
    }

    @Test
    void rejectsInvalidSelectedSlotsBeforeApplyingAnyItems() {
        final var inventory = FabricData.Items.Inventory.empty();
        for (int selected : new int[]{-1, 9, 43}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FabricData.Items.Inventory.from(new ItemStack[43], selected));
            assertThrows(IllegalArgumentException.class, () -> inventory.setHeldItemSlot(selected));
        }
    }

    @Test
    void rejectsInvalidEnderChestSize() {
        assertEquals(27, FabricData.Items.EnderChest.empty().getContents().length);
        assertThrows(IllegalArgumentException.class,
                () -> FabricData.Items.EnderChest.adapt(new ItemStack[43]));
    }
}
