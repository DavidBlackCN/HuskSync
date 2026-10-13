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

package net.william278.husksync.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.server.MinecraftServer;
import net.william278.toilet.DumpOptions;
import net.william278.toilet.Toilet;
import net.william278.toilet.dump.PluginInfo;
import net.william278.toilet.dump.ServerMeta;
import net.william278.toilet.util.FileReaderUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies Fabric diagnostics to Toilet's existing dump serializer and redaction pipeline.
 *
 * <p>Adapted for Minecraft 26.3 from
 * <a href="https://github.com/WiIIiam278/Toilet">Toilet's Apache-2.0 Fabric adapter</a>,
 * copyright William278, QarthO and contributors.</p>
 */
public final class FabricDiagnosticBridge extends Toilet {

    private final MinecraftServer server;

    public FabricDiagnosticBridge(@NotNull DumpOptions options, @NotNull MinecraftServer server) {
        super(options);
        this.server = server;
    }

    @Override
    public @NotNull List<PluginInfo> getPlugins() {
        return FabricLoader.getInstance().getAllMods().stream().map(mod -> {
            final var metadata = mod.getMetadata();
            return PluginInfo.builder()
                    .name(metadata.getName())
                    .version(metadata.getVersion().getFriendlyString())
                    .description(metadata.getDescription())
                    .authors(metadata.getAuthors().stream().map(Person::getName).toList())
                    .enabled(true)
                    .labels(getOptions().getLabelsFor(metadata.getName(), metadata.getVersion().getFriendlyString()))
                    .build();
        }).toList();
    }

    @Override
    public @NotNull ServerMeta getServerMeta() {
        final FabricLoader loader = FabricLoader.getInstance();
        return ServerMeta.builder()
                .minecraftVersion(server.getServerVersion())
                .serverJarType("fabric")
                .serverJarVersion(loader.getModContainer("fabricloader")
                        .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
                .proxyState(loader.isModLoaded("fabricproxy-lite")
                        ? ServerMeta.ProxyState.BEHIND_VELOCITY_PROXY : ServerMeta.ProxyState.NO_PROXY)
                .onlineMode(server.usesAuthentication())
                .build();
    }

    @Override
    public @NotNull Path getProjectConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(getProjectMeta().getId());
    }

    @Override
    public @NotNull String getLatestLog() {
        try {
            return FileReaderUtil.readLargeFile(FabricLoader.getInstance().getGameDir()
                    .resolve("logs").resolve("latest.log"), LATEST_LOG_MAX_LINES);
        } catch (IOException e) {
            return "Failed to read latest.log";
        }
    }
}
