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
        if (placements == null || placements.isEmpty() || fluidLookup == null || loaded == null) {
            return Set.of();
        }

        LinkedHashSet<BlockPos> scheduled = new LinkedHashSet<>();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        for (PreparedBlockPlacement placement : placements) {
            if (!this.isFluidReplayTarget(placement)) {
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

    private record SearchNode(BlockPos pos, int distance) {
    }
}
