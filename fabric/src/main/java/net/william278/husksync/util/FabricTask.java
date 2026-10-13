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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.server.MinecraftServer;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.UserDataHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public interface FabricTask extends Task {

    static ScheduledExecutorService createExecutor() {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(4,
                new ThreadFactoryBuilder().setDaemon(true).setNameFormat("HuskSync-Task-%d").build());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    default ScheduledExecutorService getExecutor() {
        return ((FabricHuskSync) getPlugin()).getTaskExecutor();
    }

    static void execute(@NotNull HuskSync plugin, @NotNull Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException | Error exception) {
            plugin.log(Level.SEVERE, "HuskSync task failed", exception);
            throw exception;
        }
    }

    class Sync extends Task.Sync implements FabricTask {

        private volatile boolean stopped;
        private ScheduledFuture<?> task;

        protected Sync(@NotNull HuskSync plugin, @NotNull Runnable runnable, long delayTicks) {
            super(plugin, runnable, delayTicks);
        }

        @Override
        public synchronized void cancel() {
            stopped = true;
            if (task != null) {
                task.cancel(false);
            }
            super.cancel();
        }

        @Override
        public synchronized void run() {
            if (stopped) {
                return;
            }
            final MinecraftServer server = ((FabricHuskSync) plugin).getMinecraftServer();
            final Runnable enqueue = () -> server.execute(() -> {
                if (!stopped && !server.isStopped() && !getExecutor().isShutdown()) {
                    FabricTask.execute(plugin, runnable);
                }
            });
            if (delayTicks <= 0) {
                enqueue.run();
            } else {
                task = getExecutor().schedule(enqueue, Math.multiplyExact(delayTicks, 50), TimeUnit.MILLISECONDS);
            }
        }
    }

    class Async extends Task.Async implements FabricTask {

        private volatile boolean stopped;
        private ScheduledFuture<?> task;

        protected Async(@NotNull HuskSync plugin, @NotNull Runnable runnable, long delayTicks) {
            super(plugin, runnable, delayTicks);
        }

        @Override
        public synchronized void cancel() {
            stopped = true;
            if (task != null) {
                task.cancel(true);
            }
            super.cancel();
        }

        @Override
        public synchronized void run() {
            if (!stopped) {
                task = getExecutor().schedule(() -> {
                    if (!stopped) {
                        FabricTask.execute(plugin, runnable);
                    }
                }, Math.multiplyExact(Math.max(0, delayTicks), 50), TimeUnit.MILLISECONDS);
            }
        }
    }

    class Repeating extends Task.Repeating implements FabricTask {

        private volatile boolean stopped;
        private ScheduledFuture<?> task;

        protected Repeating(@NotNull HuskSync plugin, @NotNull Runnable runnable, long repeatingTicks) {
            super(plugin, runnable, repeatingTicks);
        }

        @Override
        public synchronized void cancel() {
            stopped = true;
            if (task != null) {
                task.cancel(true);
            }
            super.cancel();
        }

        @Override
        public synchronized void run() {
            if (!stopped) {
                task = getExecutor().scheduleAtFixedRate(() -> {
                    if (!stopped) {
                        FabricTask.execute(plugin, runnable);
                    }
                }, 0, Math.multiplyExact(repeatingTicks, 50), TimeUnit.MILLISECONDS);
            }
        }
    }

    interface Supplier extends Task.Supplier {

        @NotNull
        @Override
        default Task.Sync getSyncTask(@NotNull Runnable runnable, @Nullable UserDataHolder user, long delayTicks) {
            return new Sync(getPlugin(), runnable, delayTicks);
        }

        @NotNull
        @Override
        default Task.Async getAsyncTask(@NotNull Runnable runnable, long delayTicks) {
            return new Async(getPlugin(), runnable, delayTicks);
        }

        @NotNull
        @Override
        default Task.Repeating getRepeatingTask(@NotNull Runnable runnable, long repeatingTicks) {
            return new Repeating(getPlugin(), runnable, repeatingTicks);
        }

        @Override
        default void cancelTasks() {
            ((FabricHuskSync) getPlugin()).getTaskExecutor().shutdownNow();
        }

    }

}
