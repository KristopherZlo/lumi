package io.github.luma.minecraft.world;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Replays neighbor notifications for persisted redstone state transitions.
 */
final class RedstoneReplayUpdatePlanner {

    private static final Set<String> POWER_PROPERTIES = Set.of(
            "powered",
            "power"
    );
    private static final String LIT_PROPERTY = "lit";

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
        if (this.signalSourceBlockChanged(currentState, targetState)) {
            return true;
        }
        for (String property : POWER_PROPERTIES) {
            if (this.propertyChanged(currentState, targetState, property)) {
                return true;
            }
        }
        return this.propertyChanged(currentState, targetState, LIT_PROPERTY)
                && (this.signalSource(currentState) || this.signalSource(targetState));
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

    private boolean signalRelevant(BlockState state) {
        return this.signalSource(state) || this.analogSignalSource(state);
    }

    private boolean signalSource(BlockState state) {
        return state != null && state.isSignalSource();
    }

    private boolean analogSignalSource(BlockState state) {
        return state != null && state.hasAnalogOutputSignal();
    }

    private boolean propertyChanged(BlockState currentState, BlockState targetState, String propertyName) {
        Optional<String> currentValue = this.propertyValue(currentState, propertyName);
        Optional<String> targetValue = this.propertyValue(targetState, propertyName);
        if (currentValue.isEmpty() && targetValue.isEmpty()) {
            return false;
        }
        return !Objects.equals(currentValue, targetValue);
    }

    private Optional<BlockPos> attachedNeighbor(BlockPos pos, BlockState state) {
        if (pos == null || state == null || !this.hasProperty(state, "face")) {
            return Optional.empty();
        }

        String face = this.propertyValue(state, "face").orElse("");
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

    private Optional<String> propertyValue(BlockState state, String propertyName) {
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
