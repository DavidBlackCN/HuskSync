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

package net.william278.husksync.mixins;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.william278.husksync.data.FabricAdvancementTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Set;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin implements FabricAdvancementTracker {

    @Shadow
    @Final
    private Map<AdvancementHolder, AdvancementProgress> progress;

    @Shadow
    @Final
    private Set<AdvancementHolder> progressChanged;

    @Shadow
    public abstract void clearTriggers();

    @Shadow
    private void startProgress(AdvancementHolder advancement, AdvancementProgress savedProgress) {
        throw new AssertionError();
    }

    @Shadow
    private void markForVisibilityUpdate(AdvancementHolder advancement) {
        throw new AssertionError();
    }

    @Shadow
    private void registerListeners(AdvancementHolder advancement) {
        throw new AssertionError();
    }

    @Override
    public void husksync$replaceProgress(Map<AdvancementHolder, AdvancementProgress> snapshots) {
        clearTriggers();
        progress.clear();
        progressChanged.clear();
        snapshots.forEach((advancement, savedProgress) -> {
            // Rebuild progress and listeners without award/revoke, which would replay rewards and commands.
            startProgress(advancement, savedProgress);
            progressChanged.add(advancement);
            markForVisibilityUpdate(advancement);
            registerListeners(advancement);
        });
    }

}
