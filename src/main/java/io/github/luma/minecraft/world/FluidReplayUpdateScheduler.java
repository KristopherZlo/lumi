package io.github.luma.minecraft.world;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;

/**
 * Reconnects vanilla fluid ticks around history replay targets.
 */
final class FluidReplayUpdateScheduler {

    private static final int MAX_FLUID_WALK_DISTANCE = 32;
    private static final int MAX_SCHEDULED_POSITIONS = 16_384;

    void schedule(ServerLevel level, Collection<PreparedBlockPlacement> placements) {
        if (level == null || placements == null || placements.isEmpty()) {
            return;
        }

        Set<BlockPos> positions = this.collectFluidUpdatePositions(
                placements,
                level::getFluidState,
                pos -> level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
        );
        for (BlockPos pos : positions) {
            FluidState fluidState = level.getFluidState(pos);
            if (!fluidState.isEmpty()) {
                level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
            }
        }
    }

    Set<BlockPos> collectFluidUpdatePositions(
            Collection<PreparedBlockPlacement> placements,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded
    ) {
        return this.collectFluidPositions(placements, this::isFluidReplayTarget, fluidLookup, loaded);
    }

    Set<BlockPos> collectFluidTailCleanupPositions(
            Collection<PreparedBlockPlacement> placements,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded
    ) {
        if (placements == null || placements.isEmpty() || fluidLookup == null || loaded == null) {
            return Set.of();
        }

        LinkedHashSet<BlockPos> cleanup = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        Set<BlockPos> traversalBarriers = this.fluidTailCleanupTraversalBarriers(placements);
        Set<BlockPos> savedFluidBarriers = this.savedFluidBarriers(placements);
        for (PreparedBlockPlacement placement : placements) {
            if (!this.isFluidRemovalTarget(placement) || !loaded.test(placement.pos())) {
                continue;
            }
            FluidState fluidState = fluidLookup.apply(placement.pos());
            if (fluidState != null && !fluidState.isEmpty() && !fluidState.isSource()) {
                cleanup.add(placement.pos().immutable());
            }
            visited.add(placement.pos().immutable());
            for (Direction direction : Direction.values()) {
                this.addOrphanedFluidComponent(
                        placement.pos().relative(direction),
                        fluidLookup,
                        loaded,
                        traversalBarriers,
                        savedFluidBarriers,
                        visited,
                        cleanup
                );
                if (cleanup.size() >= MAX_SCHEDULED_POSITIONS) {
                    break;
                }
            }
            if (cleanup.size() >= MAX_SCHEDULED_POSITIONS) {
                break;
            }
        }
        return Set.copyOf(cleanup);
    }

    private void addOrphanedFluidComponent(
            BlockPos seed,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded,
            Set<BlockPos> traversalBarriers,
            Set<BlockPos> savedFluidBarriers,
            LinkedHashSet<BlockPos> visited,
            LinkedHashSet<BlockPos> cleanup
    ) {
        if (seed == null || cleanup.size() >= MAX_SCHEDULED_POSITIONS || this.isBarrier(seed, traversalBarriers)
                || !loaded.test(seed)) {
            return;
        }
        FluidState seedFluid = fluidLookup.apply(seed);
        if (seedFluid == null || seedFluid.isEmpty()) {
            return;
        }

        FluidComponent component = this.collectFluidComponent(
                seed,
                fluidLookup,
                loaded,
                traversalBarriers,
                savedFluidBarriers,
                visited
        );
        if (component.touchesSourceOrSavedFluid()) {
            return;
        }
        for (BlockPos pos : component.nonSourcePositions()) {
            cleanup.add(pos);
            if (cleanup.size() >= MAX_SCHEDULED_POSITIONS) {
                break;
            }
        }
    }

    private FluidComponent collectFluidComponent(
            BlockPos seed,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded,
            Set<BlockPos> traversalBarriers,
            Set<BlockPos> savedFluidBarriers,
            LinkedHashSet<BlockPos> visited
    ) {
        LinkedHashSet<BlockPos> nonSourcePositions = new LinkedHashSet<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        boolean touchesSourceOrSavedFluid = false;
        this.addFluidComponentNode(seed, 0, fluidLookup, loaded, traversalBarriers, visited, queue);

        while (!queue.isEmpty()) {
            SearchNode node = queue.removeFirst();
            FluidState fluidState = fluidLookup.apply(node.pos());
            if (fluidState == null || fluidState.isEmpty()) {
                continue;
            }
            if (fluidState.isSource()) {
                touchesSourceOrSavedFluid = true;
            } else {
                nonSourcePositions.add(node.pos());
            }
            if (node.distance() >= MAX_FLUID_WALK_DISTANCE) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = node.pos().relative(direction);
                if (this.isBarrier(next, traversalBarriers)) {
                    if (this.isBarrier(next, savedFluidBarriers)) {
                        touchesSourceOrSavedFluid = true;
                    }
                    continue;
                }
                this.addFluidComponentNode(
                        next,
                        node.distance() + 1,
                        fluidLookup,
                        loaded,
                        traversalBarriers,
                        visited,
                        queue
                );
            }
        }
        return new FluidComponent(Set.copyOf(nonSourcePositions), touchesSourceOrSavedFluid);
    }

    private void addFluidComponentNode(
            BlockPos pos,
            int distance,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded,
            Set<BlockPos> barriers,
            LinkedHashSet<BlockPos> visited,
            ArrayDeque<SearchNode> queue
    ) {
        if (pos == null || this.isBarrier(pos, barriers) || !loaded.test(pos)) {
            return;
        }
        FluidState fluidState = fluidLookup.apply(pos);
        if (fluidState == null || fluidState.isEmpty()) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (visited.add(immutable)) {
            queue.addLast(new SearchNode(immutable, distance));
        }
    }

    private Set<BlockPos> fluidTailCleanupTraversalBarriers(Collection<PreparedBlockPlacement> placements) {
        LinkedHashSet<BlockPos> barriers = new LinkedHashSet<>();
        if (placements == null) {
            return Set.of();
        }
        for (PreparedBlockPlacement placement : placements) {
            if (placement == null || placement.pos() == null || placement.state() == null) {
                continue;
            }
            if (this.isFluidRemovalTarget(placement) || !placement.state().getFluidState().isEmpty()) {
                barriers.add(placement.pos().immutable());
            }
        }
        return Set.copyOf(barriers);
    }

    private Set<BlockPos> savedFluidBarriers(Collection<PreparedBlockPlacement> placements) {
        LinkedHashSet<BlockPos> barriers = new LinkedHashSet<>();
        if (placements == null) {
            return Set.of();
        }
        for (PreparedBlockPlacement placement : placements) {
            if (placement != null
                    && placement.pos() != null
                    && placement.state() != null
                    && !placement.state().getFluidState().isEmpty()) {
                barriers.add(placement.pos().immutable());
            }
        }
        return Set.copyOf(barriers);
    }

    private boolean isBarrier(BlockPos pos, Set<BlockPos> barriers) {
        return pos != null && barriers != null && barriers.contains(pos);
    }

    private Set<BlockPos> collectFluidPositions(
            Collection<PreparedBlockPlacement> placements,
            Predicate<PreparedBlockPlacement> replayTarget,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded
    ) {
        if (placements == null || placements.isEmpty() || replayTarget == null || fluidLookup == null || loaded == null) {
            return Set.of();
        }

        LinkedHashSet<BlockPos> scheduled = new LinkedHashSet<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        for (PreparedBlockPlacement placement : placements) {
            if (!replayTarget.test(placement)) {
                continue;
            }
            this.addSeed(placement.pos(), fluidLookup, loaded, scheduled, queue);
            for (Direction direction : Direction.values()) {
                this.addSeed(placement.pos().relative(direction), fluidLookup, loaded, scheduled, queue);
            }
            if (scheduled.size() >= MAX_SCHEDULED_POSITIONS) {
                break;
            }
        }

        while (!queue.isEmpty() && scheduled.size() < MAX_SCHEDULED_POSITIONS) {
            SearchNode node = queue.removeFirst();
            if (node.distance() >= MAX_FLUID_WALK_DISTANCE) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                this.addConnectedFluid(
                        node.pos().relative(direction),
                        node.distance() + 1,
                        fluidLookup,
                        loaded,
                        scheduled,
                        queue
                );
                if (scheduled.size() >= MAX_SCHEDULED_POSITIONS) {
                    break;
                }
            }
        }
        return Set.copyOf(scheduled);
    }

    private void addSeed(
            BlockPos pos,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded,
            LinkedHashSet<BlockPos> scheduled,
            ArrayDeque<SearchNode> queue
    ) {
        this.addConnectedFluid(pos, 0, fluidLookup, loaded, scheduled, queue);
    }

    private void addConnectedFluid(
            BlockPos pos,
            int distance,
            Function<BlockPos, FluidState> fluidLookup,
            Predicate<BlockPos> loaded,
            LinkedHashSet<BlockPos> scheduled,
            ArrayDeque<SearchNode> queue
    ) {
        if (pos == null || scheduled.size() >= MAX_SCHEDULED_POSITIONS || !loaded.test(pos)) {
            return;
        }
        FluidState fluidState = fluidLookup.apply(pos);
        if (fluidState == null || fluidState.isEmpty()) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (scheduled.add(immutable)) {
            queue.addLast(new SearchNode(immutable, distance));
        }
    }

    private boolean isFluidReplayTarget(PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return false;
        }
        return placement.replayHint().suppressesPostReplayFluid()
                || !placement.state().getFluidState().isEmpty();
    }

    private boolean isFluidRemovalTarget(PreparedBlockPlacement placement) {
        return placement != null
                && placement.pos() != null
                && placement.state() != null
                && placement.replayHint().suppressesPostReplayFluid()
                && placement.state().getFluidState().isEmpty();
    }

    private record SearchNode(BlockPos pos, int distance) {
    }

    private record FluidComponent(Set<BlockPos> nonSourcePositions, boolean touchesSourceOrSavedFluid) {
    }
}
