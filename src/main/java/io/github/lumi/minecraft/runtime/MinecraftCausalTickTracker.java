package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import io.github.lumi.minecraft.world.OwnedBlockEventAccess;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Associates accepted vanilla block/fluid ticks with their live root action. */
public final class MinecraftCausalTickTracker {
    private static final Map<LevelTicks<?>, Binding> BINDINGS = new WeakHashMap<>();

    private final LiveActionJournal journal;
    private final LevelTicks<Block> blockTicks;
    private final LevelTicks<Fluid> fluidTicks;
    private final CausalTokenRegistry<TickKey> tokens = new CausalTokenRegistry<>();
    private final CausalTokenRegistry<BlockEventData> blockEvents = new CausalTokenRegistry<>();
    private final OwnedBlockEventAccess eventAccess;
    private final CausalTokenRegistry<BlockEntity> blockCarriers = new CausalTokenRegistry<>();
    private final DimensionFreezeState freeze;
    private final CausalTokenRegistry<Entity> entityCarriers = new CausalTokenRegistry<>();

    public MinecraftCausalTickTracker(
            LiveActionJournal journal,
            ServerLevel level,
            DimensionFreezeState freeze,
            LevelTicks<Block> blockTicks,
            LevelTicks<Fluid> fluidTicks) {
        this.journal = Objects.requireNonNull(journal, "journal");
        eventAccess = (OwnedBlockEventAccess) Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
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
                    binding.tracker.remember(binding.tracker.tokens,
                            new TickKey(binding.kind, tick.pos(), tick.type()), action));
        }
    }

    public Optional<CausalExecution> resumeBlock(BlockPos position, Block block) {
        return resume(new TickKey(Kind.BLOCK, position, block));
    }

    public Optional<CausalExecution> resumeFluid(BlockPos position, Fluid fluid) {
        return resume(new TickKey(Kind.FLUID, position, fluid));
    }

    public void scheduledBlockEvent(BlockEventData event) {
        if (!eventAccess.lumi$hasBlockEvent(event)) {
            DirectLiveActionContext.current(journal).ifPresent(action ->
                    remember(blockEvents, event, action));
        }
    }

    public Optional<CausalExecution> resumeBlockEvent(BlockEventData event) {
        return consume(blockEvents, event);
    }

    public void rememberCarrier(BlockEntity carrier) {
        if (carrier instanceof PistonMovingBlockEntity) {
            DirectLiveActionContext.current(journal).ifPresent(action ->
                    remember(blockCarriers, carrier, action));
        }
    }

    public Optional<CausalExecution> resumeCarrier(BlockEntity carrier) {
        return blockCarriers.owner(carrier).map(action -> new CausalExecution(
                DirectLiveActionContext.resume(journal, action), () -> { }));
    }

    public void finishedCarrier(BlockEntity carrier) {
        if (carrier.isRemoved()) {
            blockCarriers.forget(carrier).ifPresent(journal::release);
        }
    }

    public void rememberCarrier(Entity carrier) {
        if (carrier instanceof FallingBlockEntity || carrier instanceof PrimedTnt) {
            DirectLiveActionContext.current(journal).ifPresent(action ->
                    remember(entityCarriers, carrier, action));
        }
    }

    public Optional<CausalExecution> resumeCarrier(Entity carrier) {
        return entityCarriers.owner(carrier).map(action -> new CausalExecution(
                DirectLiveActionContext.resume(journal, action), () -> { }));
    }

    public void finishedCarrier(Entity carrier) {
        if (carrier.isRemoved()) {
            entityCarriers.forget(carrier).ifPresent(journal::release);
        }
    }

    public void cancel(java.util.UUID action) {
        tokens.cancel(action).forEach(key -> {
            remove(key);
            journal.release(action);
        });
        blockEvents.cancel(action).forEach(event -> {
            eventAccess.lumi$removeBlockEvent(event);
            journal.release(action);
        });
        blockCarriers.cancel(action).forEach(carrier -> {
            if (carrier instanceof PistonMovingBlockEntity piston) {
                try {
                    try (var ignored = DirectLiveActionContext.resume(journal, action)) {
                        freeze.runAuthorized(piston::finalTick);
                    }
                } finally {
                    journal.release(action);
                }
            }
        });
        entityCarriers.cancel(action).forEach(carrier -> {
            try {
                freeze.runAuthorized(carrier::discard);
            } finally {
                journal.release(action);
            }
        });
    }

    public void cancelAll() {
        tokens.drain().forEach(this::remove);
        blockEvents.drain().forEach(eventAccess::lumi$removeBlockEvent);
        blockCarriers.clear();
        entityCarriers.clear();
    }

    public void close() {
        synchronized (BINDINGS) {
            BINDINGS.remove(blockTicks);
            BINDINGS.remove(fluidTicks);
        }
        tokens.clear();
        blockEvents.clear();
        blockCarriers.clear();
        entityCarriers.clear();
    }

    private Optional<CausalExecution> resume(TickKey key) {
        return consume(tokens, key);
    }

    private <K> void remember(CausalTokenRegistry<K> registry, K key, java.util.UUID action) {
        Optional<java.util.UUID> previous = registry.remember(key, action);
        if (previous.filter(action::equals).isPresent()) {
            return;
        }
        previous.ifPresent(journal::release);
        journal.retain(action);
    }

    private <K> Optional<CausalExecution> consume(CausalTokenRegistry<K> registry, K key) {
        return registry.take(key).map(action -> new CausalExecution(
                DirectLiveActionContext.resume(journal, action), () -> journal.release(action)));
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

    public static final class CausalExecution implements AutoCloseable {
        private final DirectLiveActionContext.Scope scope;
        private final Runnable completion;

        private CausalExecution(DirectLiveActionContext.Scope scope, Runnable completion) {
            this.scope = scope;
            this.completion = completion;
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                completion.run();
            }
        }
    }
}
