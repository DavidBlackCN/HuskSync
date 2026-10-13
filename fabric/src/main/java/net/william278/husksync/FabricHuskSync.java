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

package net.william278.husksync;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.minecraft.server.MinecraftServer;
import net.william278.desertwell.util.Version;
import net.william278.husksync.adapter.DataAdapter;
import net.william278.husksync.adapter.GsonAdapter;
import net.william278.husksync.adapter.SnappyGsonAdapter;
import net.william278.husksync.api.FabricHuskSyncAPI;
import net.william278.husksync.command.PluginCommand;
import net.william278.husksync.compat.FabricCommandBridge;
import net.william278.husksync.compat.FabricDiagnosticBridge;
import net.william278.husksync.config.Locales;
import net.william278.husksync.config.Server;
import net.william278.husksync.config.Settings;
import net.william278.husksync.data.*;
import net.william278.husksync.database.Database;
import net.william278.husksync.database.MongoDbDatabase;
import net.william278.husksync.database.MySqlDatabase;
import net.william278.husksync.database.PostgresDatabase;
import net.william278.husksync.event.FabricEventDispatcher;
import net.william278.husksync.event.ModLoadedCallback;
import net.william278.husksync.hook.PlanHook;
import net.william278.husksync.listener.FabricEventListener;
import net.william278.husksync.listener.LockedHandler;
import net.william278.husksync.migrator.Migrator;
import net.william278.husksync.redis.RedisManager;
import net.william278.husksync.sync.DataSyncer;
import net.william278.husksync.user.ConsoleUser;
import net.william278.husksync.user.FabricUser;
import net.william278.husksync.user.OnlineUser;
import net.william278.husksync.util.FabricTask;
import net.william278.husksync.util.LegacyConverter;
import net.william278.toilet.Toilet;
import net.william278.uniform.Uniform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

@Getter
@NoArgsConstructor
public class FabricHuskSync implements DedicatedServerModInitializer, HuskSync, FabricTask.Supplier,
        FabricEventDispatcher {

    private static final String PLATFORM_TYPE_ID = "fabric";

    private final HashMap<Identifier, Serializer<? extends Data>> serializers = Maps.newHashMap();
    private final Map<UUID, Map<Identifier, Data>> playerCustomDataStore = Maps.newConcurrentMap();
    private final Map<String, Boolean> permissions = Maps.newHashMap();
    private final List<Migrator> availableMigrators = Lists.newArrayList();
    private final Set<UUID> lockedPlayers = Sets.newConcurrentHashSet();
    private final Set<UUID> disconnectingPlayers = Sets.newConcurrentHashSet();
    private final Map<UUID, FabricUser> playerMap = Maps.newConcurrentMap();
    private final ScheduledExecutorService taskExecutor = FabricTask.createExecutor();

    private Logger logger;
    private ModContainer mod;
    private MinecraftServer minecraftServer;
    private volatile boolean disabling;
    private Gson gson;
    private MinecraftServerAudiences audiences;
    private FabricCommandBridge uniform;
    private Toilet toilet;
    private Database database;
    private RedisManager redisManager;
    private FabricEventListener eventListener;
    private DataAdapter dataAdapter;
    @Setter
    private DataSyncer dataSyncer;
    @Setter
    private Settings settings;
    @Setter
    private Locales locales;
    @Setter
    @Getter(AccessLevel.NONE)
    private Server serverName;

    @Override
    public void onInitializeServer() {
        // Get the logger and mod container
        this.logger = LoggerFactory.getLogger("HuskSync");
        this.mod = FabricLoader.getInstance().getModContainer("husksync").orElseThrow();
        this.disabling = false;
        this.gson = createGson();

        // Load settings and locales
        initialize("plugin config & locale files", (plugin) -> {
            loadSettings();
            loadLocales();
            loadServer();
            validateConfigFiles();
        });

        // Register commands
        initialize("commands", (plugin) -> {
            this.uniform = new FabricCommandBridge(mod.getMetadata().getId());
            this.uniform.register(PluginCommand.Type.create(this));
            this.uniform.registerCallback();
        });

        // Load HuskSync after server startup
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.minecraftServer = server;
            try {
                this.onEnable();
            } catch (RuntimeException | Error failure) {
                // Startup may fail after opening a connection but before constructing the event listener.
                try {
                    this.onDisable();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        });

        // Unload HuskSync before server shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> this.onDisable());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            this.minecraftServer = null;
            this.playerMap.clear();
            this.playerCustomDataStore.clear();
            this.lockedPlayers.clear();
            this.disconnectingPlayers.clear();
        });
    }

    private void onEnable() {
        // Audiences
        this.audiences = MinecraftServerAudiences.of(minecraftServer);
        this.toilet = new FabricDiagnosticBridge(getDumpOptions(), minecraftServer);

        // Check compatibility
        checkCompatibility();
        log(Level.WARNING, """
                **************
                WARNING:
                HuskSync for Fabric is still in an alpha state and is
                not considered production ready.
                **************""");

        // Prepare data adapter
        initialize("data adapter", (plugin) -> {
            if (getSettings().getSynchronization().isCompressData()) {
                this.dataAdapter = new SnappyGsonAdapter(this);
            } else {
                this.dataAdapter = new GsonAdapter(this);
            }
        });

        initialize("data serializers", (plugin) -> {
            // PERSISTENT_DATA is not registered / available on the Fabric platform
            registerSerializer(Identifier.INVENTORY, new FabricSerializer.Inventory(this));
            registerSerializer(Identifier.ENDER_CHEST, new FabricSerializer.EnderChest(this));
            registerSerializer(Identifier.ADVANCEMENTS, new FabricSerializer.Advancements(this));
            registerSerializer(Identifier.STATISTICS, new Serializer.Json<>(this, FabricData.Statistics.class));
            registerSerializer(Identifier.POTION_EFFECTS, new FabricSerializer.PotionEffects(this));
            registerSerializer(Identifier.GAME_MODE, new Serializer.Json<>(this, FabricData.GameMode.class));
            registerSerializer(Identifier.FLIGHT_STATUS, new Serializer.Json<>(this, FabricData.FlightStatus.class));
            registerSerializer(Identifier.ATTRIBUTES, new Serializer.Json<>(this, FabricData.Attributes.class));
            registerSerializer(Identifier.HEALTH, new Serializer.Json<>(this, FabricData.Health.class));
            registerSerializer(Identifier.HUNGER, new Serializer.Json<>(this, FabricData.Hunger.class));
            registerSerializer(Identifier.EXPERIENCE, new Serializer.Json<>(this, FabricData.Experience.class));
            registerSerializer(Identifier.LOCATION, new Serializer.Json<>(this, FabricData.Location.class));
            validateDependencies();
        });

        // Initialize the database
        initialize(getSettings().getDatabase().getType().getDisplayName() + " database connection", (plugin) -> {
            this.database = switch (settings.getDatabase().getType()) {
                case MYSQL, MARIADB -> new MySqlDatabase(this);
                case POSTGRES -> new PostgresDatabase(this);
                case MONGO -> new MongoDbDatabase(this);
            };
            this.database.initialize();
        });

        // Prepare redis connection
        initialize("Redis server connection", (plugin) -> {
            this.redisManager = new RedisManager(this);
            this.redisManager.initialize();
        });

        // Prepare data syncer
        initialize("data syncer", (plugin) -> {
            dataSyncer = getSettings().getSynchronization().getMode().create(this);
            dataSyncer.initialize();
        });

        // Register events
        initialize("events", (plugin) -> this.eventListener = new FabricEventListener(this));

        // Register plugin hooks
        initialize("hooks", (plugin) -> {
            if (isDependencyLoaded("Plan") && getSettings().isEnablePlanHook()) {
                new PlanHook(this).hookIntoPlan();
            }
        });

        // Register API
        initialize("api", (plugin) -> FabricHuskSyncAPI.register(this));

        // Check for updates
        this.checkForUpdates();

        ModLoadedCallback.EVENT.invoker().post(FabricHuskSyncAPI.getInstance());
    }

    private void onDisable() {
        if (this.disabling) {
            return;
        }
        // Handle shutdown
        this.disabling = true;
        final List<Throwable> failures = new ArrayList<>();

        // Complete player saves, including any other pending async saves.
        if (this.eventListener != null) {
            shutdownStep("pending player saves", this.eventListener::handlePluginDisable, failures);
            if (!failures.isEmpty() && this.dataSyncer != null) {
                // A synchronous snapshot failure must not skip waiting for saves that were already queued.
                shutdownStep("queued player saves", this.dataSyncer::awaitPendingSaves, failures);
            }
        }

        // Clear Redis checkout state after snapshots are correctly persisted
        if (this.dataSyncer != null && failures.isEmpty()) {
            shutdownStep("data syncer", this.dataSyncer::terminate, failures);
        }

        // These may already be open even when initialization failed before the event listener was registered.
        if (this.database != null) {
            shutdownStep("database connection", this.database::terminate, failures);
        }
        if (this.redisManager != null) {
            shutdownStep("Redis connection", this.redisManager::terminate, failures);
        }

        // Cancel tasks, close audiences
        if (audiences != null) {
            shutdownStep("audiences", this.audiences::close, failures);
            this.audiences = null;
        }
        shutdownStep("scheduled tasks", this::cancelTasks, failures);
        if (this.uniform != null) {
            shutdownStep("command executor", this.uniform::shutdown, failures);
        }
        FabricHuskSyncAPI.unregister();

        if (!failures.isEmpty()) {
            final IllegalStateException failure = new IllegalStateException("HuskSync shutdown did not complete cleanly");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }

        // Complete shutdown
        log(Level.INFO, "Successfully disabled HuskSync v" + getPluginVersion());
    }

    private void shutdownStep(@NotNull String name, @NotNull Runnable task, @NotNull List<Throwable> failures) {
        try {
            task.run();
        } catch (RuntimeException | Error failure) {
            log(Level.SEVERE, "Failed to close " + name, failure);
            failures.add(failure);
        }
    }


    @NotNull
    @Override
    public String getServerName() {
        return serverName.getName();
    }

    @Override
    public boolean isDependencyLoaded(@NotNull String name) {
        return FabricLoader.getInstance().isModLoaded(name.toLowerCase(Locale.ROOT));
    }

    @Override
    @NotNull
    public Set<OnlineUser> getOnlineUsers() {
        return Sets.newHashSet(playerMap.values());
    }

    @Override
    @NotNull
    public Optional<OnlineUser> getOnlineUser(@NotNull UUID uuid) {
        return Optional.ofNullable(playerMap.get(uuid));
    }

    @Override
    @NotNull
    public Uniform getUniform() {
        return uniform;
    }

    @NotNull
    @Override
    public Map<Identifier, Data> getPlayerCustomDataStore(@NotNull OnlineUser user) {
        return playerCustomDataStore.compute(
                user.getUuid(),
                (uuid, data) -> data == null ? Maps.newHashMap() : data
        );
    }

    @Override
    @Nullable
    public InputStream getResource(@NotNull String name) {
        return this.mod.findPath(name)
                .map(path -> {
                    try {
                        return Files.newInputStream(path);
                    } catch (IOException e) {
                        log(Level.WARNING, "Failed to load resource: " + name, e);
                    }
                    return null;
                })
                .orElse(this.getClass().getClassLoader().getResourceAsStream(name));
    }

    @Override
    @NotNull
    public Path getConfigDirectory() {
        final Path path = FabricLoader.getInstance().getConfigDir().resolve("husksync");
        if (!Files.isDirectory(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to create config directory", e);
            }
        }
        return path;
    }

    @Override
    public void log(@NotNull Level level, @NotNull String message, @NotNull Throwable... throwable) {
        LoggingEventBuilder logEvent = logger.makeLoggingEventBuilder(
                switch (level.getName()) {
                    case "WARNING" -> org.slf4j.event.Level.WARN;
                    case "SEVERE" -> org.slf4j.event.Level.ERROR;
                    default -> org.slf4j.event.Level.INFO;
                }
        );
        if (throwable.length >= 1) {
            logEvent = logEvent.setCause(throwable[0]);
        }
        logEvent.log(message);
    }

    @NotNull
    @Override
    public ConsoleUser getConsole() {
        return new ConsoleUser(audiences);
    }

    @Override
    @NotNull
    public Version getPluginVersion() {
        return Version.fromString(mod.getMetadata().getVersion().getFriendlyString(), "-");
    }

    @Override
    @NotNull
    public Version getMinecraftVersion() {
        return Version.fromString(minecraftServer.getServerVersion());
    }

    @NotNull
    @Override
    public String getPlatformType() {
        return PLATFORM_TYPE_ID;
    }

    @Override
    public boolean isStrictSnapshotDeserialization() {
        return true;
    }

    @Override
    @NotNull
    public String getServerVersion() {
        return String.format("%s %s/%s", getPlatformType(), FabricLoader.getInstance()
                .getModContainer("fabricloader").map(l -> l.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"), minecraftServer.getServerVersion());
    }

    @Override
    public Optional<LegacyConverter> getLegacyConverter() {
        return Optional.empty();
    }

    @Override
    @NotNull
    public LockedHandler getLockedHandler() {
        return eventListener;
    }

    @Override
    @NotNull
    public FabricHuskSync getPlugin() {
        return this;
    }

}
