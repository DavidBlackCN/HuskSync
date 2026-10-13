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

import net.william278.husksync.FabricHuskSync;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FabricTaskTests {

    @Test
    void asynchronousTasksHonorTheirDelay() throws InterruptedException {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            final CountDownLatch invoked = new CountDownLatch(1);
            final AtomicLong executedAt = new AtomicLong();
            final long scheduledAt = System.nanoTime();
            plugin.runAsyncDelayed(() -> {
                executedAt.set(System.nanoTime());
                invoked.countDown();
            }, 4);
            assertTrue(invoked.await(3, TimeUnit.SECONDS), "The delayed task must eventually run");
            assertTrue(executedAt.get() - scheduledAt >= TimeUnit.MILLISECONDS.toNanos(150),
                    "A four-tick delay must not run immediately");
        } finally {
            plugin.cancelTasks();
        }
    }

    @Test
    void cancellingADelayedTaskPreventsExecution() throws InterruptedException {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            final CountDownLatch invoked = new CountDownLatch(1);
            final Task.Async task = plugin.runAsyncDelayed(invoked::countDown, 4);
            task.cancel();
            assertFalse(invoked.await(350, TimeUnit.MILLISECONDS));
        } finally {
            plugin.cancelTasks();
        }
    }

    @Test
    void stoppingOnePluginDoesNotShutDownTheNextInstance() throws InterruptedException {
        final FabricHuskSync previous = new FabricHuskSync();
        final FabricHuskSync current = new FabricHuskSync();
        previous.cancelTasks();
        try {
            assertNotSame(previous.getTaskExecutor(), current.getTaskExecutor());
            final CountDownLatch invoked = new CountDownLatch(1);
            current.runAsync(invoked::countDown);
            assertTrue(invoked.await(3, TimeUnit.SECONDS));
        } finally {
            current.cancelTasks();
        }
    }

}
