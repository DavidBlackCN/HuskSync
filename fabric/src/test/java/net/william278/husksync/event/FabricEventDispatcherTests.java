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

package net.william278.husksync.event;

import net.minecraft.world.InteractionResult;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.user.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FabricEventDispatcherTests {

    @Test
    void explicitlyCancelledSaveEventsAreNotPersisted() {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            final DataSaveEvent event = plugin.getDataSaveEvent(new User(UUID.randomUUID(), "TestPlayer"), null);
            FabricDataSaveCallback.EVENT.register(received -> {
                if (received == event) {
                    received.setCancelled(true);
                }
                return InteractionResult.PASS;
            });
            assertTrue(plugin.fireIsCancelled(event), "Explicit cancellation must work even when the callback returns PASS");
        } finally {
            plugin.cancelTasks();
        }
    }

    @Test
    void explicitlyCancelledPreSyncEventsAreNotApplied() {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            final PreSyncEvent event = plugin.getPreSyncEvent(null, null);
            FabricPreSyncCallback.EVENT.register(received -> {
                if (received == event) {
                    received.setCancelled(true);
                }
                return InteractionResult.PASS;
            });
            assertTrue(plugin.fireIsCancelled(event));
        } finally {
            plugin.cancelTasks();
        }
    }

    @Test
    void unmodifiedSaveEventsRemainAllowed() {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            final DataSaveEvent event = plugin.getDataSaveEvent(new User(UUID.randomUUID(), "TestPlayer"), null);
            assertFalse(plugin.fireIsCancelled(event));
        } finally {
            plugin.cancelTasks();
        }
    }

}
