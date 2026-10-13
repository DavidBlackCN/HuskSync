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

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.minecraft.commands.CommandSourceStack;
import net.william278.uniform.BaseCommand;
import net.william278.uniform.Command;
import net.william278.uniform.CommandUser;
import net.william278.uniform.Permission;
import net.william278.uniform.Uniform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Connects Uniform's platform-independent command graph to the unobfuscated Fabric command API.
 *
 * <p>This adapter uses the public API of Uniform 1.3.9 (GPL-3.0-or-later), documented at
 * <a href="https://github.com/WiIIiam278/Uniform">the upstream project</a>. The Uniform common library
 * remains responsible for argument parsing, suggestions, permission predicates and execution.</p>
 */
public final class FabricCommandBridge implements Uniform {

    private final String namespace;
    private final List<BaseCommand<CommandSourceStack>> commands = new ArrayList<>();
    private Function<Object, CommandUser> commandUserSupplier = source -> new SourceUser((CommandSourceStack) source);

    public FabricCommandBridge(@NotNull String namespace) {
        this.namespace = namespace;
    }

    public void registerCallback() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> registerTo(dispatcher));
    }

    // Register again whenever Minecraft rebuilds its command dispatcher, including /reload.
    void registerTo(@NotNull CommandDispatcher<CommandSourceStack> dispatcher) {
        for (BaseCommand<CommandSourceStack> command : commands) {
            final LiteralCommandNode<CommandSourceStack> node = command.build();
            dispatcher.getRoot().addChild(node);
            final Set<String> aliases = new LinkedHashSet<>(command.getAliases());
            command.getAliases().forEach(alias -> aliases.add(namespace + ":" + alias));
            aliases.add(namespace + ":" + command.getName());
            for (String alias : aliases) {
                dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal(alias)
                        .requires(node.getRequirement())
                        .executes(node.getCommand())
                        .redirect(node));
            }
        }
    }

    @Override
    public void register(@NotNull Command... commands) {
        for (Command command : commands) {
            this.commands.add(new ServerCommand(command));
        }
    }

    @Override
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final <S, T extends BaseCommand<S>> void register(@NotNull T... commands) {
        for (T command : commands) {
            // Uniform's generic platform API requires callers to supply this platform's source type.
            this.commands.add((BaseCommand<CommandSourceStack>) command);
        }
    }

    @Override
    public @NotNull Function<Object, CommandUser> getCommandUserSupplier() {
        return commandUserSupplier;
    }

    @Override
    public void setCommandUserSupplier(@NotNull Function<Object, CommandUser> supplier) {
        commandUserSupplier = Objects.requireNonNull(supplier);
    }

    private final class ServerCommand extends BaseCommand<CommandSourceStack> {

        private ServerCommand(@NotNull Command command) {
            super(command.getName(), command.getDescription(), command.getAliases());
            setExecutionScope(command.getScope());
            command.getPermission().ifPresent(this::setPermission);
            command.provide(this);
        }

        @Override
        public void addSubCommand(@NotNull Command command) {
            addSubCommand(new ServerCommand(command));
        }

        @Override
        public Uniform getUniform() {
            return FabricCommandBridge.this;
        }
    }

    private record SourceUser(@NotNull CommandSourceStack source) implements CommandUser {

        @Override
        public @NotNull Audience getAudience() {
            return MinecraftServerAudiences.of(source.getServer()).audience(source);
        }

        @Override
        public @Nullable String getName() {
            return source.isPlayer() ? source.getTextName() : null;
        }

        @Override
        public @Nullable UUID getUuid() {
            return source.getPlayer() == null ? null : source.getPlayer().getUUID();
        }

        @Override
        public boolean checkPermission(@NotNull Permission permission) {
            final boolean owner = source.permissions().hasPermission(
                    net.minecraft.server.permissions.Permissions.COMMANDS_OWNER
            );
            return Permissions.check(source, permission.node(), permission.defaultValue().check(owner));
        }
    }
}
