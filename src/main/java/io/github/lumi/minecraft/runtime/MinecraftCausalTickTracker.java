package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import io.github.lumi.minecraft.world.OwnedBlockEventAccess;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Associates accepted vanilla block/fluid ticks with their live root action. */
public final class MinecraftCausalTickTracker {
    static final int MAX_CAUSAL_DEPTH = 32;
    private static final Map<LevelTicks<?>, Binding> BINDINGS = new WeakHashMap<>();

    private final LiveActionJournal journal;
    private final LevelTicks<Block> blockTicks;
    private final LevelTicks<Fluid> fluidTicks;
    private final CausalTokenRegistry<TickKey, DirectLiveActionContext.CausalRoot> tokens =
            new CausalTokenRegistry<>();
    private final CausalTokenRegistry<BlockEventData, DirectLiveActionContext.CausalRoot>
            blockEvents = new CausalTokenRegistry<>();
    private final OwnedBlockEventAccess eventAccess;
    private final CausalTokenRegistry<BlockEntity, DirectLiveActionContext.CausalRoot>
            blockCarriers = new CausalTokenRegistry<>();
    private final DimensionFreezeState freeze;
    private final CausalTokenRegistry<Entity, DirectLiveActionContext.CausalRoot> entityCarriers =
            new CausalTokenRegistry<>();
    private final Set<java.util.UUID> depthLimitLogged = new HashSet<>();

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
            binding.tracker.rememberCurrent(
                    binding.tracker.tokens,
                    new TickKey(binding.kind, tick.pos(), tick.type()));
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
            rememberCurrent(blockEvents, event);
        }
    }

    public Optional<CausalExecution> resumeBlockEvent(BlockEventData event) {
        return consume(blockEvents, event);
    }

    public void rememberCarrier(BlockEntity carrier) {
        if (carrier instanceof PistonMovingBlockEntity) {
            rememberCurrent(blockCarriers, carrier);
        }
    }

    public Optional<CausalExecution> resumeCarrier(BlockEntity carrier) {
        return blockCarriers.owner(carrier).map(root -> new CausalExecution(
                DirectLiveActionContext.resume(journal, root.action(), root.depth()), () -> { }));
    }

    public void finishedCarrier(BlockEntity carrier) {
        if (carrier.isRemoved()) {
            blockCarriers.forget(carrier).ifPresent(root -> journal.release(root.action()));
        }
    }

    public void rememberCarrier(Entity carrier) {
        if (carrier instanceof FallingBlockEntity || carrier instanceof PrimedTnt || carrier instanceof AbstractArrow) {
            rememberCurrent(entityCarriers, carrier);
        }
    }

    public Optional<CausalExecution> resumeCarrier(Entity carrier) {
        return entityCarriers.owner(carrier).map(root -> new CausalExecution(
                DirectLiveActionContext.resume(journal, root.action(), root.depth()), () -> { }));
    }

    public void finishedCarrier(Entity carrier) {
        if (carrier.isRemoved()) {
            entityCarriers.forget(carrier).ifPresent(root -> journal.release(root.action()));
        }
    }

    public void rememberAppliedCarrier(java.util.UUID action, Entity carrier) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(carrier, "carrier");
        if (isTransientCarrier(carrier) && !carrier.isRemoved()) {
            remember(entityCarriers, carrier,
                    new DirectLiveActionContext.CausalRoot(action, 1));
        }
    }

    public boolean cancellationMayChangeBlocks(java.util.UUID action) {
        Objects.requireNonNull(action, "action");
        return blockCarriers.anyMatch(root -> root.action().equals(action));
    }

    public boolean cancel(java.util.UUID action, Predicate<java.util.UUID> preserveEntity) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(preserveEntity, "preserveEntity");
        Set<BlockEntity> cancelledBlockCarriers =
                blockCarriers.cancel(root -> root.action().equals(action));
        cancelledBlockCarriers.forEach(carrier -> {
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
        Set<TickKey> cancelledTicks = tokens.cancel(root -> root.action().equals(action));
        cancelledTicks.forEach(key -> {
            remove(key);
            journal.release(action);
        });
        Set<BlockEventData> cancelledEvents =
                blockEvents.cancel(root -> root.action().equals(action));
        cancelledEvents.forEach(event -> {
            eventAccess.lumi$removeBlockEvent(event);
            journal.release(action);
        });
        Set<Entity> cancelledEntityCarriers =
                entityCarriers.cancel(root -> root.action().equals(action));
        cancelledEntityCarriers.forEach(carrier -> {
            try {
                if (!preserveEntity.test(carrier.getUUID())) {
                    freeze.runAuthorized(carrier::discard);
                }
            } finally {
                journal.release(action);
            }
        });
        depthLimitLogged.remove(action);
        return !cancelledBlockCarriers.isEmpty() || !cancelledTicks.isEmpty()
                || !cancelledEvents.isEmpty() || !cancelledEntityCarriers.isEmpty();
    }

    public void cancelAll() {
        tokens.drain().forEach(this::remove);
        blockEvents.drain().forEach(eventAccess::lumi$removeBlockEvent);
        blockCarriers.clear();
        entityCarriers.clear();
        depthLimitLogged.clear();
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
        depthLimitLogged.clear();
    }

    private Optional<CausalExecution> resume(TickKey key) {
        return consume(tokens, key);
    }

    private <K> void rememberCurrent(
            CausalTokenRegistry<K, DirectLiveActionContext.CausalRoot> registry, K key) {
        DirectLiveActionContext.currentRoot(journal).ifPresent(root ->
                root.child(MAX_CAUSAL_DEPTH).ifPresentOrElse(
                        child -> remember(registry, key, child),
                        () -> logDepthLimit(root.action())));
    }

    private <K> void remember(
            CausalTokenRegistry<K, DirectLiveActionContext.CausalRoot> registry,
            K key,
            DirectLiveActionContext.CausalRoot root) {
        Optional<DirectLiveActionContext.CausalRoot> previous = registry.remember(key, root);
        if (previous.filter(root::equals).isPresent()) {
            return;
        }
        previous.ifPresent(value -> journal.release(value.action()));
        journal.retain(root.action());
    }

    private <K> Optional<CausalExecution> consume(
            CausalTokenRegistry<K, DirectLiveActionContext.CausalRoot> registry, K key) {
        return registry.take(key).map(root -> new CausalExecution(
                DirectLiveActionContext.resume(journal, root.action(), root.depth()),
                () -> journal.release(root.action())));
    }

    private void logDepthLimit(java.util.UUID action) {
        if (depthLimitLogged.add(action)) {
            LumiMod.LOGGER.info(
                    "Lumi live action {} stopped inheriting delayed work after {} generations",
                    action, MAX_CAUSAL_DEPTH);
        }
    }

    private static boolean isTransientCarrier(Entity carrier) {
        return carrier instanceof FallingBlockEntity
                || carrier instanceof PrimedTnt
                || carrier instanceof AbstractArrow;
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
