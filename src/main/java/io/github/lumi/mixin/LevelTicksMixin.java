package io.github.lumi.mixin;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
abstract class LevelTicksMixin<T> implements OwnedTickAccess<T> {
    @Shadow @Final private Long2ObjectMap<LevelChunkTicks<T>> allContainers;
    @Shadow @Final private Queue<ScheduledTick<T>> toRunThisTick;
    @Shadow @Final private List<ScheduledTick<T>> alreadyRunThisTick;
    @Shadow @Final private Set<ScheduledTick<?>> toRunThisTickSet;

    @Inject(method = "schedule", at = @At("HEAD"))
    private void lumi$captureScheduledTick(ScheduledTick<T> tick, CallbackInfo callback) {
        MinecraftCausalTickTracker.scheduled((LevelTicks<?>) (Object) this, tick);
    }

    @Override
    public void lumi$remove(BlockPos position, T type) {
        // ponytail: stale earliest-container time may cause one harmless early scan;
        // update LevelTicks internals only if profiling shows that scan matters.
        java.util.function.Predicate<ScheduledTick<T>> matches =
                tick -> tick.pos().equals(position) && tick.type() == type;
        LevelChunkTicks<T> container = allContainers.get(
                ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4));
        if (container != null) {
            container.removeIf(matches);
        }
        lumi$removeQueued(matches);
    }

    @Override
    public void lumi$removeSections(Set<SectionKey> sections) {
        Map<Long, Set<Integer>> sectionYs = new HashMap<>();
        for (SectionKey section : sections) {
            sectionYs.computeIfAbsent(
                    ChunkPos.asLong(section.chunkX(), section.chunkZ()),
                    ignored -> new HashSet<>()).add(section.sectionY());
        }
        Predicate<ScheduledTick<T>> matches = tick -> {
            BlockPos position = tick.pos();
            Set<Integer> ys = sectionYs.get(ChunkPos.asLong(
                    position.getX() >> 4, position.getZ() >> 4));
            return ys != null && ys.contains(position.getY() >> 4);
        };
        for (long chunk : sectionYs.keySet()) {
            LevelChunkTicks<T> container = allContainers.get(chunk);
            if (container != null) {
                container.removeIf(matches);
            }
        }
        lumi$removeQueued(matches);
    }

    @SuppressWarnings("unchecked")
    private void lumi$removeQueued(Predicate<ScheduledTick<T>> matches) {
        toRunThisTick.removeIf(matches);
        alreadyRunThisTick.removeIf(matches);
        toRunThisTickSet.removeIf(tick -> matches.test((ScheduledTick<T>) tick));
    }
}
