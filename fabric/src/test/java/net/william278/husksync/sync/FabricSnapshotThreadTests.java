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

package net.william278.husksync.sync;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.william278.desertwell.util.Version;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.config.Settings;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.FabricUserDataHolder;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.data.UserDataHolder;
import net.william278.husksync.event.Event;
import net.william278.husksync.redis.RedisManager;
import net.william278.husksync.user.OnlineUser;
import net.william278.husksync.user.User;
import net.william278.husksync.util.Task;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class FabricSnapshotThreadTests {

    @Test
    void asynchronousSaveCapturesOnServerBeforeStartingTrackedWrites() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            final TestSyncer syncer = new TestSyncer(plugin);
            final CompletableFuture<Void> result = CompletableFuture.supplyAsync(
                    () -> syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.WORLD_SAVE)
            ).get(2, TimeUnit.SECONDS);
            assertEquals(0, user.captures);
            assertEquals(0, syncer.submissions);
            assertFalse(result.isDone());

            plugin.tick();
            assertEquals(1, user.captures);
            assertEquals(1, plugin.events);
            assertEquals(1, syncer.submissions);
            assertFalse(result.isDone(), "The returned future must also wait for the database/Redis write");
            syncer.write.complete(null);
            result.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownCancelsUncapturedSavesWithoutWaitingForAnotherTick() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            final TestSyncer syncer = new TestSyncer(plugin);
            final CompletableFuture<Void> result = CompletableFuture.supplyAsync(
                    () -> syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.WORLD_SAVE)
            ).get(2, TimeUnit.SECONDS);
            assertTimeout(Duration.ofSeconds(1), syncer::awaitPendingSaves);
            assertTrue(result.isCancelled());
            plugin.tick();
            assertEquals(0, user.captures, "Cancelled queued work must not save a stale session later");
            assertEquals(0, syncer.submissions);
        }
    }

    @Test
    void disconnectAndShutdownCaptureImmediatelyWithoutSchedulingATick() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            final TestSyncer syncer = new TestSyncer(plugin);
            syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.DISCONNECT);
            assertEquals(1, user.captures);
            assertEquals(1, plugin.events);
            assertEquals(1, syncer.submissions);
            assertTrue(plugin.queue.isEmpty());

            plugin.disabling = true;
            syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.SERVER_SHUTDOWN);
            assertEquals(2, user.captures);
            assertEquals(1, plugin.events, "Shutdown saves must not dispatch another server event");
            assertEquals(2, syncer.submissions);
            assertTrue(plugin.queue.isEmpty());
        }
    }

    @Test
    void directAsynchronousSnapshotFailsBeforeReadingLiveData() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            final ExecutionException error = assertThrows(ExecutionException.class, () ->
                    CompletableFuture.supplyAsync(() -> user.createSnapshot(DataSnapshot.SaveCause.API))
                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals(0, user.captures);
        }
    }

    @Test
    void snapshotFailureDoesNotStartTrackedWrites() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            user.failCapture = true;
            final TestSyncer syncer = new TestSyncer(plugin);
            final CompletableFuture<Void> result = CompletableFuture.supplyAsync(
                    () -> syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.WORLD_SAVE)
            ).get(2, TimeUnit.SECONDS);
            plugin.tick();
            assertTrue(result.isCompletedExceptionally());
            assertEquals(0, syncer.submissions);
        }
    }

    @Test
    void queuedSaveDoesNotCaptureAUserWhoDisconnectedBeforeTheTick() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            final TestSyncer syncer = new TestSyncer(plugin);
            final CompletableFuture<Void> result = CompletableFuture.supplyAsync(
                    () -> syncer.saveCurrentUserData(user, DataSnapshot.SaveCause.WORLD_SAVE)
            ).get(2, TimeUnit.SECONDS);
            user.disconnected = true;
            plugin.tick();
            assertTrue(result.isCancelled());
            assertEquals(0, user.captures);
            assertEquals(0, syncer.submissions);
        }
    }

    @Test
    void localRedisQueryCapturesOnServerAndReturnsTheSnapshot() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = new TestUser(plugin);
            plugin.onlineUser = user;
            final RedisManager redis = new RedisManager(plugin);
            final CompletableFuture<Optional<DataSnapshot.Packed>> result = CompletableFuture.supplyAsync(
                    () -> redis.getOnlineUserData(UUID.randomUUID(), user, DataSnapshot.SaveCause.API)
            ).get(2, TimeUnit.SECONDS);
            final CompletableFuture<Void> continuation = result.thenAccept(snapshot ->
                    assertFalse(plugin.isEventThread(), "API continuations must not perform database work on the tick"));
            assertEquals(0, user.captures);
            assertEquals(0, plugin.asyncTasks, "A local player must not trigger a redundant network request");
            assertFalse(result.isDone());
            plugin.tick();
            assertEquals(DataSnapshot.SaveCause.API.name(),
                    result.get(2, TimeUnit.SECONDS).orElseThrow().getSaveCause().name());
            continuation.get(2, TimeUnit.SECONDS);
            assertEquals(1, user.captures);
        }
    }

    @Test
    void localRedisEmptyAndFailedCapturesAlsoCompleteOffTheServerThread() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            try (TestPlugin plugin = new TestPlugin()) {
                final TestUser user = new TestUser(plugin);
                user.failCapture = fail;
                user.disconnected = !fail;
                plugin.onlineUser = user;
                final RedisManager redis = new RedisManager(plugin);
                final CompletableFuture<Optional<DataSnapshot.Packed>> result = CompletableFuture.supplyAsync(
                        () -> redis.getOnlineUserData(UUID.randomUUID(), user, DataSnapshot.SaveCause.API)
                ).get(2, TimeUnit.SECONDS);
                final CompletableFuture<Void> continuation = result.handle((snapshot, error) -> {
                    assertFalse(plugin.isEventThread());
                    if (fail) {
                        assertInstanceOf(IllegalStateException.class, error);
                    } else {
                        assertNull(error);
                        assertTrue(snapshot.isEmpty());
                    }
                    return null;
                });
                plugin.tick();
                continuation.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void localRedisQueryTimesOutWhenTheServerCannotRunAnotherTick() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            plugin.setCaptureTimeout(20);
            final TestUser user = new TestUser(plugin);
            plugin.onlineUser = user;
            final RedisManager redis = new RedisManager(plugin);
            final CompletableFuture<Optional<DataSnapshot.Packed>> result = CompletableFuture.supplyAsync(
                    () -> redis.getOnlineUserData(UUID.randomUUID(), user, DataSnapshot.SaveCause.API)
            ).get(2, TimeUnit.SECONDS);
            final ExecutionException error = assertThrows(ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, error.getCause());
            plugin.tick();
            assertEquals(0, user.captures, "Timed-out requests must not capture data on a later tick");
        }
    }

    private static class TestSyncer extends DataSyncer {
        private final CompletableFuture<Void> write = new CompletableFuture<>();
        private int submissions;

        private TestSyncer(TestPlugin plugin) {
            super(plugin);
        }

        @Override
        protected CompletableFuture<Void> runTrackedAsync(User user, Runnable task) {
            assertTrue(plugin.isEventThread(), "Only the already captured snapshot may enter the save executor");
            submissions++;
            return write;
        }

        @Override
        public void syncApplyUserData(OnlineUser user) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void syncSaveUserData(OnlineUser user) {
            throw new UnsupportedOperationException();
        }
    }

    private static class TestPlugin extends FabricHuskSync implements AutoCloseable {
        private final Thread serverThread = Thread.currentThread();
        private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private final Settings settings;
        private int events;
        private int asyncTasks;
        private boolean disabling;
        private TestUser onlineUser;

        private TestPlugin() throws ReflectiveOperationException {
            final var constructor = Settings.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            settings = constructor.newInstance();
            setCaptureTimeout(5000);
        }

        private void setCaptureTimeout(int milliseconds) throws ReflectiveOperationException {
            final var field = Settings.SynchronizationSettings.class.getDeclaredField("networkLatencyMilliseconds");
            field.setAccessible(true);
            field.setInt(settings.getSynchronization(), milliseconds);
        }

        private void tick() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }

        @Override
        public Task.Sync runSync(Runnable runnable, UserDataHolder user) {
            queue.add(runnable);
            return null;
        }

        @Override
        public Task.Async runAsync(Runnable runnable) {
            asyncTasks++;
            CompletableFuture.runAsync(runnable);
            return null;
        }

        @Override
        public Optional<OnlineUser> getOnlineUser(UUID uuid) {
            return Optional.ofNullable(onlineUser).filter(user -> user.getUuid().equals(uuid))
                    .map(OnlineUser.class::cast);
        }

        @Override
        public boolean isEventThread() {
            return Thread.currentThread() == serverThread;
        }

        @Override
        public <T extends Event> boolean fireIsCancelled(T event) {
            assertTrue(isEventThread());
            events++;
            return false;
        }

        @Override
        public boolean isDisabling() {
            return disabling;
        }

        @Override
        public Settings getSettings() {
            return settings;
        }

        @Override
        public String getServerName() {
            return "snapshot-test";
        }

        @Override
        public Version getMinecraftVersion() {
            return Version.fromString("26.3");
        }

        @Override
        public void log(Level level, String message, Throwable... error) {
            // Failure propagation is asserted by the tests; this fixture has no Fabric logger.
        }

        @Override
        public void close() {
            cancelTasks();
        }
    }

    private static class TestUser extends OnlineUser implements FabricUserDataHolder {
        private final TestPlugin plugin;
        private int captures;
        private boolean failCapture;
        private boolean disconnected;

        private TestUser(TestPlugin plugin) {
            super(UUID.randomUUID(), "SnapshotTest");
            this.plugin = plugin;
        }

        @Override
        public Map<Identifier, Data> getData() {
            assertTrue(plugin.isEventThread());
            captures++;
            if (failCapture) {
                throw new IllegalStateException("Capture failed");
            }
            return Map.of();
        }

        @Override
        public TestPlugin getPlugin() {
            return plugin;
        }

        @Override
        public String getUsername() {
            return getName();
        }

        @Override
        public boolean hasDisconnected() {
            return disconnected;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public ServerPlayer getPlayer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPlayer(ServerPlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendToast(Component title, Component description, String material, String background) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showGui(Data.Items.Items items, Component title, boolean editable, int size,
                            Consumer<Data.Items.Items> onClose) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isLocked() {
            return false;
        }

        @Override
        public boolean isNpc() {
            return false;
        }
    }
}
