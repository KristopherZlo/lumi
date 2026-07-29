package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.DimensionFreezeState;
import io.github.lumi.minecraft.world.MinecraftSectionCapture;
import io.github.lumi.minecraft.world.OwnedBlockEventAccess;
import io.github.lumi.minecraft.world.OwnedTickAccess;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Associates accepted vanilla block/fluid ticks with their live root action. */
public final class MinecraftCausalTickTracker {
    static final int MAX_CAUSAL_DEPTH = 512;
    private static final Map<LevelTicks<?>, Binding> BINDINGS = new WeakHashMap<>();

    private final LiveActionJournal journal;
    private final LevelTicks<Block> blockTicks;
    private final LevelTicks<Fluid> fluidTicks;
    private final CausalTokenRegistry<TickKey, DirectLiveActionContext.CausalRoot> tokens =
            new CausalTokenRegistry<>();
    private final Set<TickKey> authorizedTicks = new HashSet<>();
    private final CausalTokenRegistry<BlockEventData, DirectLiveActionContext.CausalRoot>
            blockEvents = new CausalTokenRegistry<>();
    private final Set<BlockEventData> authorizedBlockEvents = new HashSet<>();
    private final OwnedBlockEventAccess eventAccess;
    private final ServerLevel level;
    private final CausalTokenRegistry<BlockEntity, DirectLiveActionContext.CausalRoot>
            blockCarriers = new CausalTokenRegistry<>();
    private final Set<BlockEntity> changedBlockCarriers = new HashSet<>();
    private final Set<BlockEntity> authorizedBlockCarriers = new HashSet<>();
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
        this.level = Objects.requireNonNull(level, "level");
        eventAccess = (OwnedBlockEventAccess) level;
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
                    binding.tracker.authorizedTicks,
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
            rememberCurrent(blockEvents, authorizedBlockEvents, event);
        }
    }

    public Optional<CausalExecution> resumeBlockEvent(BlockEventData event) {
        return consume(blockEvents, event);
    }

    public void rememberCarrier(BlockEntity carrier) {
        Optional<DirectLiveActionContext.CausalRoot> current =
                DirectLiveActionContext.currentRoot(journal);
        if (current.isPresent() && blockCarriers.owner(carrier)
                .filter(owner -> owner.action().equals(
                        current.orElseThrow().action()))
                .isPresent()) {
            changedBlockCarriers.add(carrier);
            return;
        }
        Optional<DirectLiveActionContext.CausalRoot> root =
                rememberCurrent(blockCarriers, carrier);
        if (root.isPresent()) {
            changedBlockCarriers.add(carrier);
        } else if (carrier instanceof PistonMovingBlockEntity
                && freeze.isAuthorizedMutation()) {
            authorizedBlockCarriers.add(carrier);
        }
    }

    public Optional<CausalExecution> resumeCarrier(BlockEntity carrier) {
        if (!(carrier instanceof PistonMovingBlockEntity)) {
            changedBlockCarriers.remove(carrier);
        }
        return resumeBlockCarrier(carrier);
    }

    public Optional<CausalExecution> resumeCarrierMutation(BlockEntity carrier) {
        return resumeBlockCarrier(carrier);
    }

    private Optional<CausalExecution> resumeBlockCarrier(BlockEntity carrier) {
        return blockCarriers.owner(carrier).map(root -> new CausalExecution(
                DirectLiveActionContext.resume(journal, root.action(), root.depth()), () -> { }));
    }

    public void finishedCarrier(BlockEntity carrier) {
        boolean unchanged = !(carrier instanceof PistonMovingBlockEntity)
                && !changedBlockCarriers.remove(carrier);
        if (carrier.isRemoved() || unchanged) {
            blockCarriers.forget(carrier).ifPresent(root -> journal.release(root.action()));
        }
    }

    public void rememberCarrier(Entity carrier) {
        if (isCausalCarrier(carrier)) {
            Set<DirectLiveActionContext.CausalRoot> activeTnt = carrier instanceof PrimedTnt
                    ? entityCarriers.owners(entity -> entity instanceof PrimedTnt)
                    : Set.of();
            Optional<DirectLiveActionContext.CausalRoot> root =
                    rememberCurrent(entityCarriers, carrier);
            if (carrier instanceof PrimedTnt && root.isPresent()) {
                joinTntWave(root.orElseThrow().action(), activeTnt);
            }
        }
    }

    public void rememberMinecartsAt(BlockPos position) {
        if (DirectLiveActionContext.currentRoot(journal).isEmpty()) {
            return;
        }
        level.getEntities(
                        (Entity) null, new AABB(position).inflate(0.5D),
                        entity -> entity instanceof AbstractMinecart minecart
                                && minecart.getCurrentBlockPosOrRailBelow().equals(position))
                .forEach(this::rememberCarrier);
    }

    public Optional<CausalExecution> resumeCarrier(Entity carrier) {
        return entityCarriers.owner(carrier).map(root -> new CausalExecution(
                DirectLiveActionContext.resume(journal, root.action(), root.depth()), () -> { }));
    }

    public void finishedCarrier(Entity carrier, boolean changed) {
        if (carrier.isRemoved()
                || (carrier instanceof AbstractMinecart && !changed)) {
            entityCarriers.forget(carrier).ifPresent(root -> journal.release(root.action()));
        }
    }

    public void finishedCarrier(Entity carrier) {
        finishedCarrier(carrier, true);
    }

    public void rememberAppliedCarrier(java.util.UUID action, Entity carrier) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(carrier, "carrier");
        if (isCausalCarrier(carrier) && !carrier.isRemoved()) {
            remember(entityCarriers, carrier,
                    new DirectLiveActionContext.CausalRoot(action, 1));
        }
    }

    /** Joins a frozen Undo selection to all still-active TNT roots. */
    public int joinActiveTntRoots(java.util.UUID selectedAction) {
        Set<DirectLiveActionContext.CausalRoot> active =
                entityCarriers.owners(entity -> entity instanceof PrimedTnt);
        return joinTntWave(Objects.requireNonNull(
                selectedAction, "selectedAction"), active);
    }

    public boolean cancellationMayChangeBlocks(java.util.UUID action) {
        Objects.requireNonNull(action, "action");
        return blockCarriers.owners(
                        carrier -> carrier instanceof PistonMovingBlockEntity)
                .stream().anyMatch(root -> root.action().equals(action));
    }

    public boolean cancel(java.util.UUID action, Predicate<java.util.UUID> preserveEntity) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(preserveEntity, "preserveEntity");
        Set<BlockEntity> cancelledBlockCarriers =
                blockCarriers.cancel(root -> root.action().equals(action));
        boolean finalizedPiston = cancelledBlockCarriers.stream()
                .anyMatch(PistonMovingBlockEntity.class::isInstance);
        cancelledBlockCarriers.forEach(carrier -> {
            try {
                if (carrier instanceof PistonMovingBlockEntity piston) {
                    try (var ignored = DirectLiveActionContext.resume(journal, action)) {
                        freeze.runAuthorized(piston::finalTick);
                    }
                }
            } finally {
                journal.release(action);
            }
        });
        changedBlockCarriers.removeAll(cancelledBlockCarriers);
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
        return finalizedPiston || !cancelledTicks.isEmpty()
                || !cancelledEvents.isEmpty() || !cancelledEntityCarriers.isEmpty();
    }

    public void cancelAll() {
        tokens.drain().forEach(this::remove);
        blockEvents.drain().forEach(eventAccess::lumi$removeBlockEvent);
        cancelAuthorizedWork();
        blockCarriers.clear();
        changedBlockCarriers.clear();
        authorizedBlockCarriers.clear();
        entityCarriers.clear();
        depthLimitLogged.clear();
    }

    public void close() {
        synchronized (BINDINGS) {
            BINDINGS.remove(blockTicks);
            BINDINGS.remove(fluidTicks);
        }
        tokens.clear();
        authorizedTicks.clear();
        blockEvents.clear();
        authorizedBlockEvents.clear();
        blockCarriers.clear();
        changedBlockCarriers.clear();
        authorizedBlockCarriers.clear();
        entityCarriers.clear();
        depthLimitLogged.clear();
    }

    /** Removes delayed player work whose restored sections replaced its state. */
    public void cancelSections(Set<SectionKey> sections) {
        Set<SectionKey> restored = Set.copyOf(
                Objects.requireNonNull(sections, "sections"));
        if (restored.isEmpty()) {
            return;
        }
        java.util.function.Predicate<BlockPos> matches =
                position -> restored.contains(MinecraftSectionCapture.key(position));
        ((OwnedTickAccess<?>) blockTicks).lumi$removeSections(restored);
        ((OwnedTickAccess<?>) fluidTicks).lumi$removeSections(restored);
        eventAccess.lumi$removeBlockEventsWhere(matches);
        var cancelledTicks = tokens.cancelKeys(
                key -> matches.test(key.position()));
        cancelledTicks.forEach((key, root) -> {
            journal.release(root.action());
        });
        var cancelledEvents = blockEvents.cancelKeys(
                event -> matches.test(event.pos()));
        cancelledEvents.forEach((event, root) -> {
            journal.release(root.action());
        });
        var cancelledBlockCarriers = blockCarriers.cancelKeys(
                carrier -> restored.contains(MinecraftSectionCapture.key(
                        carrier.getBlockPos())));
        cancelledBlockCarriers.values().forEach(root ->
                journal.release(root.action()));
        changedBlockCarriers.removeAll(cancelledBlockCarriers.keySet());
        entityCarriers.cancelKeys(
                carrier -> restored.contains(MinecraftSectionCapture.key(
                        carrier.blockPosition())))
                .values().forEach(root -> journal.release(root.action()));
        cancelAuthorizedWork();
    }

    /** Drops vanilla work created as a side effect of verified frozen apply. */
    public void cancelAuthorizedWork() {
        Set.copyOf(authorizedBlockCarriers).forEach(carrier -> {
            freeze.runAuthorized(() -> {
                carrier.setRemoved();
                if (level.getBlockEntity(carrier.getBlockPos()) == carrier) {
                    level.removeBlockEntity(carrier.getBlockPos());
                }
            });
        });
        authorizedBlockCarriers.clear();
        Set.copyOf(authorizedTicks).forEach(this::remove);
        authorizedTicks.clear();
        Set.copyOf(authorizedBlockEvents).forEach(eventAccess::lumi$removeBlockEvent);
        authorizedBlockEvents.clear();
    }

    private int joinTntWave(
            java.util.UUID action,
            Set<DirectLiveActionContext.CausalRoot> active) {
        Optional<java.util.UUID> actor = journal.owner(action);
        if (actor.isEmpty()) {
            return 0;
        }
        Set<java.util.UUID> matching = active.stream()
                .map(DirectLiveActionContext.CausalRoot::action)
                .filter(other -> journal.owner(other).equals(actor))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        matching.forEach(other -> journal.mergeGroups(other, action));
        return matching.size();
    }

    private Optional<CausalExecution> resume(TickKey key) {
        return consume(tokens, key);
    }

    private <K> Optional<DirectLiveActionContext.CausalRoot> rememberCurrent(
            CausalTokenRegistry<K, DirectLiveActionContext.CausalRoot> registry, K key) {
        return rememberCurrent(registry, null, key);
    }

    private <K> Optional<DirectLiveActionContext.CausalRoot> rememberCurrent(
            CausalTokenRegistry<K, DirectLiveActionContext.CausalRoot> registry,
            Set<K> authorized,
            K key) {
        Optional<DirectLiveActionContext.CausalRoot> current =
                DirectLiveActionContext.currentRoot(journal);
        if (current.isEmpty()) {
            if (authorized != null && freeze.isAuthorizedMutation()) {
                authorized.add(key);
            }
            return Optional.empty();
        }
        Optional<DirectLiveActionContext.CausalRoot> child =
                current.orElseThrow().child(MAX_CAUSAL_DEPTH);
        child.ifPresentOrElse(
                root -> remember(registry, key, root),
                () -> logDepthLimit(current.orElseThrow().action()));
        return child;
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
                || carrier instanceof ItemEntity
                || carrier instanceof PrimedTnt
                || carrier instanceof AbstractArrow;
    }

    private static boolean isCausalCarrier(Entity carrier) {
        return isTransientCarrier(carrier) || carrier instanceof AbstractMinecart;
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
