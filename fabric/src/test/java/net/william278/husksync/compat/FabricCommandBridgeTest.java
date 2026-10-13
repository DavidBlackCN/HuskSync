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
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.audience.Audience;
import net.minecraft.commands.CommandSourceStack;
import net.william278.uniform.BaseCommand;
import net.william278.uniform.Command;
import net.william278.uniform.CommandUser;
import net.william278.uniform.Permission;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricCommandBridgeTest {

    private static final List<String> NAMES = List.of("inventory", "inv", "husksync:inventory", "husksync:inv");

    @Test
    void allAliasesEnforceRootPermission() {
        final var bridge = bridge(new TestUser(false, false));
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        bridge.register(command(new CountDownLatch(1), Command.ExecutionScope.ALL));
        bridge.registerTo(dispatcher);

        for (String name : NAMES) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(name, (CommandSourceStack) null));
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(name + " view", (CommandSourceStack) null));
        }
    }

    @Test
    void aliasesExecuteDefaultAndSubcommandAfterDispatcherReload() throws Exception {
        final var bridge = bridge(new TestUser(false, true));
        final var executions = new CountDownLatch(NAMES.size() * 2 * 2);
        bridge.register(command(executions, Command.ExecutionScope.IN_GAME));

        // Minecraft supplies a new dispatcher on reload; both must receive the complete command tree.
        for (int reload = 0; reload < 2; reload++) {
            final var dispatcher = new CommandDispatcher<CommandSourceStack>();
            bridge.registerTo(dispatcher);
            for (String name : NAMES) {
                assertEquals(1, dispatcher.execute(name, (CommandSourceStack) null));
                assertEquals(1, dispatcher.execute(name + " view", (CommandSourceStack) null));
            }
        }
        assertTrue(executions.await(5, TimeUnit.SECONDS));
    }

    @Test
    void consoleCannotInvokePlayerOnlyCommandThroughAlias() {
        final var bridge = bridge(new TestUser(true, true));
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        bridge.register(command(new CountDownLatch(1), Command.ExecutionScope.IN_GAME));
        bridge.registerTo(dispatcher);

        for (String name : NAMES) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(name, (CommandSourceStack) null));
        }
    }

    @Test
    void consoleCanInvokeConsoleOnlyCommand() throws Exception {
        final var bridge = bridge(new TestUser(true, true));
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        final var executions = new CountDownLatch(1);
        bridge.register(command(executions, Command.ExecutionScope.CONSOLE));
        bridge.registerTo(dispatcher);
        assertEquals(1, dispatcher.execute("husksync:inventory", (CommandSourceStack) null));
        assertTrue(executions.await(5, TimeUnit.SECONDS));
    }

    private static FabricCommandBridge bridge(TestUser user) {
        final var bridge = new FabricCommandBridge("husksync");
        bridge.setCommandUserSupplier(source -> user);
        return bridge;
    }

    private static Command command(CountDownLatch executions, Command.ExecutionScope scope) {
        return new Command("inventory", List.of("inv"), "Inventory", Permission.defaultIfOp("husksync.inventory"), scope) {
            @Override
            public void provide(@NotNull BaseCommand<?> command) {
                command.setDefaultExecutor(context -> executions.countDown());
                command.addSubCommand("view", sub -> sub.setDefaultExecutor(context -> executions.countDown()));
            }
        };
    }

    @AfterAll
    static void stopUniformExecutor() {
        new FabricCommandBridge("husksync").shutdown();
    }

    private record TestUser(boolean console, boolean permitted) implements CommandUser {

        @Override
        public @NotNull Audience getAudience() {
            return Audience.empty();
        }

        @Override
        public String getName() {
            return console ? null : "Player";
        }

        @Override
        public UUID getUuid() {
            return console ? null : new UUID(0, 1);
        }

        @Override
        public boolean checkPermission(@NotNull Permission permission) {
            return permitted;
        }
    }
}
