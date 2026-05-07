package io.github.luma.minecraft.world;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Replays neighbor notifications only when the signal-capable block itself changes.
 *
 * <p>Persisted redstone properties such as power, powered, and lit are restored
 * as exact history state. Re-simulating those property transitions during replay
 * can schedule a fresh pulse after undo/redo and move the world away from the
 * state being restored.
 */
final class RedstoneReplayUpdatePlanner {

    void propagate(ServerLevel level, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (level == null || pos == null) {
            return;
        }

        RedstoneReplayUpdateBatch batch = this.plan(pos, currentState, targetState);
        if (batch.isEmpty()) {
            return;
        }
        if (WorldRedstoneReplayUpdateContext.enqueue(batch)) {
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
                || this.playerInputSignalChanged(currentState, targetState);
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
            return this.signalRelevant(currentState) || this.signalRelevant(targetState);
        }
        return currentState.getBlock() != targetState.getBlock()
                && (this.signalRelevant(currentState) || this.signalRelevant(targetState));
    }

    private boolean playerInputSignalChanged(BlockState currentState, BlockState targetState) {
        if (currentState == null || targetState == null || currentState.getBlock() != targetState.getBlock()) {
            return false;
        }
        if (!this.isPlayerInputControl(currentState) && !this.isPlayerInputControl(targetState)) {
            return false;
        }
        return this.propertyChanged(currentState, targetState, "powered")
                || this.propertyChanged(currentState, targetState, "power");
    }

    private boolean isPlayerInputControl(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof TripWireBlock
                || block instanceof TripWireHookBlock;
    }

    private boolean propertyChanged(BlockState currentState, BlockState targetState, String propertyName) {
        Optional<String> currentValue = this.stringPropertyValue(currentState, propertyName);
        Optional<String> targetValue = this.stringPropertyValue(targetState, propertyName);
        return currentValue.isPresent() && targetValue.isPresent() && !currentValue.equals(targetValue);
    }

    private boolean signalRelevant(BlockState state) {
        return this.signalSource(state) || this.analogSignalSource(state);
    }

    private boolean signalSource(BlockState state) {
        return state != null && state.isSignalSource();
    }

    private boolean analogSignalSource(BlockState state) {
        return state != null && state.hasAnalogOutputSignal();
    }

    private Optional<BlockPos> attachedNeighbor(BlockPos pos, BlockState state) {
        if (pos == null || state == null || !this.hasProperty(state, "face")) {
            return Optional.empty();
        }

        String face = this.stringPropertyValue(state, "face").orElse("");
        if ("floor".equals(face)) {
            return Optional.of(pos.below());
        }
        if ("ceiling".equals(face)) {
            return Optional.of(pos.above());
        }
        Direction facing = this.directionProperty(state, "facing");
        return facing == null
                ? Optional.empty()
                : Optional.of(pos.relative(facing.getOpposite()));
    }

    private boolean hasProperty(BlockState state, String propertyName) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> stringPropertyValue(BlockState state, String propertyName) {
        if (state == null) {
            return Optional.empty();
        }
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return Optional.of(String.valueOf(state.getValue(property)).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private Direction directionProperty(BlockState state, String propertyName) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(propertyName)) {
                continue;
            }
            Comparable<?> value = state.getValue(property);
            return value instanceof Direction direction ? direction : null;
        }
        return null;
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
