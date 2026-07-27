package io.github.lumi.mixin;

import io.github.lumi.minecraft.runtime.MinecraftCausalTickTracker;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
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
        lumi$removeTicks(matches);
    }

    @Override
    public void lumi$removeWhere(Predicate<BlockPos> positions) {
        lumi$removeTicks(tick -> positions.test(tick.pos()));
    }

    @SuppressWarnings("unchecked")
    private void lumi$removeTicks(Predicate<ScheduledTick<T>> matches) {
        allContainers.values().forEach(container -> container.removeIf(matches));
        toRunThisTick.removeIf(matches);
        alreadyRunThisTick.removeIf(matches);
        toRunThisTickSet.removeIf(tick -> matches.test((ScheduledTick<T>) tick));
    }
}
