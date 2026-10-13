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

import net.william278.desertwell.util.Version;
import net.william278.husksync.FabricHuskSync;
import net.william278.husksync.HuskSync;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDecodingTests {

    @Test
    void fabricRejectsPartialSnapshotRestores() {
        final FabricHuskSync plugin = new FabricHuskSync();
        try {
            assertTrue(plugin.isStrictSnapshotDeserialization());
        } finally {
            plugin.cancelTasks();
        }
    }

    @Test
    void strictUnpackRejectsTheWholeSnapshotAndRetainsTheCause() {
        final Fixture fixture = new Fixture(true);
        final DataSnapshot.Packed snapshot = fixture.snapshot();
        final var failure = assertThrows(Serializer.DeserializationException.class,
                () -> snapshot.unpack(fixture.plugin));
        assertSame(fixture.decodeFailure, failure.getCause());
        assertTrue(failure.getMessage().contains(fixture.invalid.toString()));
        assertTrue(failure.getMessage().contains(snapshot.getId().toString()));
        assertEquals(2, fixture.decoded.get(), "A later failure must also discard earlier decoded data");
    }

    @Test
    void failedStrictUnpackReportsFailureWithoutSchedulingAnyApply() {
        final Fixture fixture = new Fixture(true);
        final UserDataHolder user = (UserDataHolder) Proxy.newProxyInstance(
                UserDataHolder.class.getClassLoader(), new Class<?>[]{UserDataHolder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlugin" -> fixture.plugin;
                    case "getUsername" -> "TestPlayer";
                    default -> InvocationHandler.invokeDefault(proxy, method, args);
                });
        final AtomicReference<Boolean> applied = new AtomicReference<>();
        user.applySnapshot(fixture.snapshot(), applied::set);
        assertEquals(Boolean.FALSE, applied.get());
        assertEquals(1, fixture.logs.get());
    }

    @Test
    void defaultPolicyPreservesOtherPlatformsPartialDecodeBehavior() {
        final Fixture fixture = new Fixture(false);
        assertFalse(fixture.plugin.isStrictSnapshotDeserialization());
        final var unpacked = fixture.snapshot().unpack(fixture.plugin);
        assertEquals(Map.of(fixture.valid, fixture.value), unpacked.getData());
        assertEquals(1, fixture.logs.get());
    }

    @Test
    void strictUnpackIgnoresUnknownAndDisabledTypesWithoutDecodingThem() {
        final Fixture fixture = new Fixture(true);
        fixture.invalid.setEnabled(false);
        final DataSnapshot.Packed snapshot = fixture.snapshot();
        snapshot.data.put("missing_mod:unregistered", "unreadable");
        assertEquals(Map.of(fixture.valid, fixture.value), snapshot.unpack(fixture.plugin).getData());
        assertEquals(1, fixture.decoded.get());
        assertEquals(0, fixture.logs.get());
    }

    private static final class Fixture {
        private final Identifier valid = Identifier.from("test", "valid");
        private final Identifier invalid = Identifier.from("test", "invalid");
        private final Data value = (user, plugin) -> fail("Failed snapshots must never be applied");
        private final IllegalArgumentException decodeFailure = new IllegalArgumentException("Unknown item component");
        private final AtomicInteger decoded = new AtomicInteger();
        private final AtomicInteger logs = new AtomicInteger();
        private final HuskSync plugin;

        private Fixture(boolean strict) {
            valid.setEnabled(true);
            invalid.setEnabled(true);
            final Serializer<Data> serializer = new Serializer<>() {
                @Override
                public Data deserialize(String serialized) {
                    decoded.incrementAndGet();
                    if (serialized.equals("invalid")) {
                        throw decodeFailure;
                    }
                    return value;
                }

                @Override
                public String serialize(Data data) {
                    throw new AssertionError("Unpacking must not serialize data");
                }
            };
            final Map<Identifier, Serializer<Data>> serializers = Map.of(valid, serializer, invalid, serializer);
            plugin = (HuskSync) Proxy.newProxyInstance(HuskSync.class.getClassLoader(), new Class<?>[]{HuskSync.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getSerializers")) {
                            return serializers;
                        }
                        if (strict && method.getName().equals("isStrictSnapshotDeserialization")) {
                            return true;
                        }
                        if (method.getName().equals("log")) {
                            logs.incrementAndGet();
                            return null;
                        }
                        if (method.getName().equals("runSync") || method.getName().equals("runAsync")) {
                            throw new AssertionError("Failed snapshots must not schedule an apply or success callback");
                        }
                        if (method.isDefault()) {
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        }
                        throw new AssertionError("Unexpected plugin call: " + method.getName());
                    });
        }

        private DataSnapshot.Packed snapshot() {
            final Map<String, String> data = new LinkedHashMap<>();
            data.put(valid.toString(), "valid");
            data.put(invalid.toString(), "invalid");
            return new DataSnapshot.Packed(UUID.randomUUID(), false, OffsetDateTime.now(),
                    DataSnapshot.SaveCause.DISCONNECT.name(), "TestServer", data,
                    Version.fromString("26.3"), "fabric", DataSnapshot.CURRENT_FORMAT_VERSION);
        }
    }
}
