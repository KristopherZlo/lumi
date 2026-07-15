package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Associates accepted vanilla block/fluid ticks with their live root action. */
public final class MinecraftCausalTickTracker {
    private static final Map<LevelTicks<?>, Binding> BINDINGS = new WeakHashMap<>();

    private final LiveActionJournal journal;
    private final LevelTicks<Block> blockTicks;
    private final LevelTicks<Fluid> fluidTicks;
    private final CausalTokenRegistry<TickKey> tokens = new CausalTokenRegistry<>();

    public MinecraftCausalTickTracker(
            LiveActionJournal journal,
            LevelTicks<Block> blockTicks,
            LevelTicks<Fluid> fluidTicks) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.blockTicks = Objects.requireNonNull(blockTicks, "blockTicks");
        this.fluidTicks = Objects.requireNonNull(fluidTicks, "fluidTicks");
        synchronized (BINDINGS) {
            BINDINGS.put(blockTicks, new Binding(this, Kind.BLOCK));
            BINDINGS.put(fluidTicks, new Binding(this, Kind.FLUID));
        }
    }

    public static void scheduled(LevelTicks<?> owner, ScheduledTick<?> tick) {
        Binding binding;
        synchronized (BINDINGS) {
            binding = BINDINGS.get(owner);
        }
        if (binding != null) {
            DirectLiveActionContext.current(binding.tracker.journal).ifPresent(action ->
                    binding.tracker.tokens.remember(
                            new TickKey(binding.kind, tick.pos(), tick.type()), action));
        }
    }

    public Optional<DirectLiveActionContext.Scope> resumeBlock(BlockPos position, Block block) {
        return resume(new TickKey(Kind.BLOCK, position, block));
    }

    public Optional<DirectLiveActionContext.Scope> resumeFluid(BlockPos position, Fluid fluid) {
        return resume(new TickKey(Kind.FLUID, position, fluid));
    }

    public void cancel(java.util.UUID action) {
        tokens.cancel(action).forEach(this::remove);
    }

    public void cancelAll() {
        tokens.drain().forEach(this::remove);
    }

    public void close() {
        synchronized (BINDINGS) {
            BINDINGS.remove(blockTicks);
            BINDINGS.remove(fluidTicks);
        }
        tokens.clear();
    }

    private Optional<DirectLiveActionContext.Scope> resume(TickKey key) {
        return tokens.take(key).map(action -> DirectLiveActionContext.resume(journal, action));
    }

    @SuppressWarnings("unchecked")
    private void remove(TickKey key) {
        if (key.kind == Kind.BLOCK) {
            ((OwnedTickAccess<Block>) blockTicks).lumi$remove(key.position, (Block) key.type);
        } else {
            ((OwnedTickAccess<Fluid>) fluidTicks).lumi$remove(key.position, (Fluid) key.type);
        }
    }

    private enum Kind { BLOCK, FLUID }

    private record TickKey(Kind kind, BlockPos position, Object type) { }

    private record Binding(MinecraftCausalTickTracker tracker, Kind kind) { }
}
