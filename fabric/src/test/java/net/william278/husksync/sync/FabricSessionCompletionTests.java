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

import de.exlll.configlib.YamlConfigurations;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.william278.desertwell.util.Version;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.config.Locales;
import net.william278.husksync.config.Settings;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.FabricUserDataHolder;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.data.UserDataHolder;
import net.william278.husksync.database.Database;
import net.william278.husksync.database.MySqlDatabase;
import net.william278.husksync.event.Event;
import net.william278.husksync.event.SyncCompleteEvent;
import net.william278.husksync.redis.RedisManager;
import net.william278.husksync.user.OnlineUser;
import net.william278.husksync.user.User;
import net.william278.husksync.util.Task;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class FabricSessionCompletionTests {

    @Test
    void alreadyReplacedSessionCannotStartCompletion() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser old = plugin.connect();
            plugin.connect();
            old.completeSync(true, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            assertTrue(plugin.queue.isEmpty());
            assertEquals(0, plugin.ensuredUsers);
            assertEquals(0, plugin.events);
            assertEquals(0, plugin.notifications);
            assertEquals(0, plugin.unlocks);
        }
    }

    @Test
    void reconnectBeforeQueuedCompletionSuppressesNotificationEventAndUnlock() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser old = plugin.connect();
            old.completeSync(true, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            plugin.connect();
            plugin.tick();
            assertEquals(1, plugin.ensuredUsers);
            assertEquals(0, plugin.events);
            assertEquals(0, plugin.notifications);
            assertEquals(0, plugin.unlocks);
        }
    }

    @Test
    void eventListenerReplacingSessionCannotUnlockItsReplacement() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser old = plugin.connect();
            plugin.onEvent = plugin::connect;
            old.completeSync(true, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            plugin.tick();
            assertEquals(1, plugin.events);
            assertEquals(1, plugin.notifications);
            assertEquals(0, plugin.unlocks);
            assertNotSame(old, plugin.current);
        }
    }

    @Test
    void currentSessionCompletesOnServerThreadAndStillHonorsEventCancellation() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser user = plugin.connect();
            user.completeSync(true, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            assertEquals(0, plugin.unlocks);
            plugin.tick();
            assertEquals(1, plugin.unlocks);
            assertEquals(1, plugin.notifications);

            plugin.cancelEvent = true;
            user.completeSync(true, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            plugin.tick();
            assertEquals(1, plugin.unlocks);
            assertEquals(2, plugin.events);
        }
    }

    @Test
    void staleFailureCannotNotifyAReplacementSession() throws Exception {
        try (TestPlugin plugin = new TestPlugin()) {
            final TestUser old = plugin.connect();
            old.completeSync(false, DataSnapshot.UpdateCause.SYNCHRONIZED, plugin);
            plugin.connect();
            plugin.tick();
            assertEquals(0, plugin.notifications);
            assertEquals(0, plugin.events);
            assertEquals(0, plugin.unlocks);
        }
    }

    @Test
    void bothDisconnectSaveCallbacksOnlyUnlockWhenNoSessionIsOnline() throws Exception {
        for (boolean lockstep : new boolean[]{false, true}) {
            try (TestPlugin plugin = new TestPlugin()) {
                final TestUser old = plugin.connect();
                final DataSyncer syncer = lockstep ? new TestLockstepSyncer(plugin) : new TestDelaySyncer(plugin);
                syncer.syncSaveUserData(old);
                final DataSnapshot.Packed saved = DataSnapshot.builder(plugin)
                        .saveCause(DataSnapshot.SaveCause.DISCONNECT).buildAndPack();
                plugin.connect();
                plugin.afterSave.accept(old, saved);
                assertEquals(0, plugin.unlocks);
                plugin.tick();
                assertEquals(0, plugin.unlocks, "The old save must leave the new login locked");

                plugin.current = null;
                plugin.afterSave.accept(old, saved);
                plugin.tick();
                assertEquals(1, plugin.unlocks, "A completed offline save must still release its lock");
            }
        }
    }

    private static class TestDelaySyncer extends DelayDataSyncer {
        private final TestPlugin testPlugin;

        private TestDelaySyncer(TestPlugin plugin) {
            super(plugin);
            testPlugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> takeSnapshotAndSave(OnlineUser user, DataSnapshot.SaveCause cause,
                                                               BiConsumer<User, DataSnapshot.Packed> after) {
            testPlugin.afterSave = after;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        protected CompletableFuture<Void> runTrackedAsync(User user, Runnable task) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class TestLockstepSyncer extends LockstepDataSyncer {
        private final TestPlugin testPlugin;

        private TestLockstepSyncer(TestPlugin plugin) {
            super(plugin);
            testPlugin = plugin;
        }

        @Override
        protected CompletableFuture<Void> takeSnapshotAndSave(OnlineUser user, DataSnapshot.SaveCause cause,
                                                               BiConsumer<User, DataSnapshot.Packed> after) {
            testPlugin.afterSave = after;
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class TestPlugin extends FabricHuskSync implements AutoCloseable {
        private final UUID uuid = UUID.randomUUID();
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private final Settings settings;
        private final Locales locales;
        private final Database database;
        private final RedisManager redis;
        private TestUser current;
        private Runnable onEvent = () -> { };
        private BiConsumer<User, DataSnapshot.Packed> afterSave;
        private boolean ticking;
        private boolean cancelEvent;
        private int ensuredUsers;
        private int notifications;
        private int events;
        private int unlocks;

        private TestPlugin() throws ReflectiveOperationException {
            final var settingsConstructor = Settings.class.getDeclaredConstructor();
            settingsConstructor.setAccessible(true);
            settings = settingsConstructor.newInstance();
            locales = YamlConfigurations.read(new ByteArrayInputStream("""
                    locales:
                      synchronization_complete: 'Sync completed'
                      synchronization_failed: 'Sync failed'
                    """.getBytes(StandardCharsets.UTF_8)), Locales.class);
            assertTrue(locales.getLocale("synchronization_complete").isPresent());
            assertTrue(locales.getLocale("synchronization_failed").isPresent());
            database = new MySqlDatabase(this) {
                @Override
                public void ensureUser(User user) {
                    assertFalse(ticking, "Database work must stay outside the scheduled server completion");
                    ensuredUsers++;
                }
            };
            redis = new RedisManager(this) {
                @Override
                public boolean setUserData(User user, DataSnapshot.Packed data) {
                    return true;
                }

                @Override
                public void setUserCheckedOut(User user, boolean checkedOut) {
                    // This fixture exercises local session locks; no Redis connection is opened.
                }
            };
        }

        private TestUser connect() {
            current = new TestUser(this, uuid);
            return current;
        }

        private void tick() {
            ticking = true;
            try {
                Runnable task;
                while ((task = queue.poll()) != null) {
                    task.run();
                }
            } finally {
                ticking = false;
            }
        }

        @Override
        public Task.Sync runSync(Runnable runnable, UserDataHolder user) {
            queue.add(runnable);
            return null;
        }

        @Override
        public Task.Sync runSync(Runnable runnable) {
            queue.add(runnable);
            return null;
        }

        @Override
        public <T extends Event> boolean fireIsCancelled(T event) {
            assertTrue(ticking);
            events++;
            onEvent.run();
            return cancelEvent;
        }

        @Override
        public SyncCompleteEvent getSyncCompleteEvent(OnlineUser user) {
            return () -> user;
        }

        @Override
        public Optional<OnlineUser> getOnlineUser(UUID uuid) {
            return Optional.ofNullable(current);
        }

        @Override
        public void unlockPlayer(UUID uuid) {
            assertTrue(ticking, "Connection changes and unlocks must share the server thread");
            unlocks++;
        }

        @Override
        public Settings getSettings() {
            return settings;
        }

        @Override
        public Locales getLocales() {
            return locales;
        }

        @Override
        public Database getDatabase() {
            return database;
        }

        @Override
        public RedisManager getRedisManager() {
            return redis;
        }

        @Override
        public String getServerName() {
            return "session-test";
        }

        @Override
        public Version getMinecraftVersion() {
            return Version.fromString("26.3");
        }

        @Override
        public void log(Level level, String message, Throwable... error) {
            fail(message, error.length > 0 ? error[0] : null);
        }

        @Override
        public void close() {
            cancelTasks();
        }
    }

    private static class TestUser extends OnlineUser implements FabricUserDataHolder {
        private final TestPlugin plugin;

        private TestUser(TestPlugin plugin, UUID uuid) {
            super(uuid, "SessionTest");
            this.plugin = plugin;
        }

        @Override
        public boolean hasDisconnected() {
            return plugin.current != this;
        }

        @Override
        public void sendMessage(Component component) {
            assertTrue(plugin.ticking);
            plugin.notifications++;
        }

        @Override
        public void sendActionBar(Component component) {
            sendMessage(component);
        }

        @Override
        public TestPlugin getPlugin() {
            return plugin;
        }

        @Override
        public Map<Identifier, Data> getData() {
            return Map.of();
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
        public void showGui(Data.Items items, Component title, boolean editable, int size,
                            Consumer<Data.Items> onClose) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isLocked() {
            return true;
        }

        @Override
        public boolean isNpc() {
            return false;
        }
    }
}
