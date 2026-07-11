package io.github.luma.minecraft.world;

import io.github.luma.minecraft.debug.HistoryDebugLog;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Replays neighbor notifications only when the signal-capable block itself changes.
 *
 * <p>Persisted redstone properties such as power, powered, and lit are restored
 * as exact history state. Re-simulating those property transitions during replay
 * can schedule a fresh pulse after restore and move the world away from the
 * state being restored.
 */
final class RedstoneReplayUpdatePlanner {

    private final HistoryDebugLog historyDebugLog = new HistoryDebugLog();
    private final MechanismStatePolicy mechanismStatePolicy = new MechanismStatePolicy();

    void propagate(ServerLevel level, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (level == null || pos == null) {
            return;
        }

        RedstoneReplayUpdateBatch batch = this.plan(pos, currentState, targetState);
        if (batch.isEmpty()) {
            return;
        }
        boolean queued = WorldRedstoneReplayUpdateContext.enqueue(batch);
        this.historyDebugLog.logRedstoneReplayPlan(
                pos,
                currentState,
                targetState,
                batch.updates().size(),
                queued
        );
        if (queued) {
            return;
        }
        batch.propagate(level);
    }

    RedstoneReplayUpdateBatch plan(BlockPos pos, BlockState currentState, BlockState targetState) {
        if (pos == null || !this.requiresPropagation(currentState, targetState)) {
            return RedstoneReplayUpdateBatch.empty();
        }

        Block sourceBlock = this.sourceBlock(currentState, targetState);
        List<RedstoneReplayUpdate> updates = new ArrayList<>();
        for (BlockPos updatePos : this.updatePositions(pos, currentState, targetState)) {
            updates.add(new RedstoneReplayUpdate(updatePos, sourceBlock));
        }
        return RedstoneReplayUpdateBatch.of(updates);
    }

    boolean requiresPropagation(BlockState currentState, BlockState targetState) {
        if (Objects.equals(currentState, targetState)) {
            return false;
        }
        return this.signalSourceBlockChanged(currentState, targetState)
                || this.playerInputSignalChanged(currentState, targetState)
                || this.replayMechanismStateChanged(currentState, targetState);
    }

    Set<BlockPos> updatePositions(BlockPos pos, BlockState currentState, BlockState targetState) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (pos == null) {
            return positions;
        }
        positions.add(pos.immutable());
        this.attachedNeighbor(pos, currentState).ifPresent(positions::add);
        this.attachedNeighbor(pos, targetState).ifPresent(positions::add);
        return Collections.unmodifiableSet(positions);
    }

    private boolean signalSourceBlockChanged(BlockState currentState, BlockState targetState) {
        if (currentState == null || targetState == null) {
            return this.mechanismStatePolicy.signalRelevant(currentState)
                    || this.mechanismStatePolicy.signalRelevant(targetState);
        }
        return currentState.getBlock() != targetState.getBlock()
                && (this.mechanismStatePolicy.signalRelevant(currentState)
                || this.mechanismStatePolicy.signalRelevant(targetState));
    }

    private boolean playerInputSignalChanged(BlockState currentState, BlockState targetState) {
        if (currentState == null || targetState == null || currentState.getBlock() != targetState.getBlock()) {
            return false;
        }
        if (!this.mechanismStatePolicy.isPlayerInputControl(currentState)
                && !this.mechanismStatePolicy.isPlayerInputControl(targetState)) {
            return false;
        }
        return this.mechanismStatePolicy.propertyChanged(currentState, targetState, "powered")
                || this.mechanismStatePolicy.propertyChanged(currentState, targetState, "power");
    }

    private boolean replayMechanismStateChanged(BlockState currentState, BlockState targetState) {
        if (currentState == null || targetState == null || currentState.getBlock() != targetState.getBlock()) {
            return false;
        }
        if (this.isPistonAnimationState(currentState) || this.isPistonAnimationState(targetState)) {
            return false;
        }
        return this.mechanismStatePolicy.isMechanismRelevant(currentState)
                || this.mechanismStatePolicy.isMechanismRelevant(targetState);
    }

    private boolean isPistonAnimationState(BlockState state) {
        return state != null
                && (state.is(Blocks.PISTON)
                || state.is(Blocks.STICKY_PISTON)
                || state.is(Blocks.PISTON_HEAD)
                || state.is(Blocks.MOVING_PISTON));
    }

    private Optional<BlockPos> attachedNeighbor(BlockPos pos, BlockState state) {
        return this.mechanismStatePolicy.attachedNeighbor(pos, state);
    }

    private Block sourceBlock(BlockState currentState, BlockState targetState) {
        if (targetState != null && !targetState.isAir()) {
            return targetState.getBlock();
        }
        if (currentState != null && !currentState.isAir()) {
            return currentState.getBlock();
        }
        return net.minecraft.world.level.block.Blocks.AIR;
    }
}
