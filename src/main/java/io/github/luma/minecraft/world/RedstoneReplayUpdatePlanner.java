package io.github.luma.minecraft.world;

import java.util.LinkedHashSet;
import java.util.Locale;
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

    private static final Set<String> REDSTONE_PROPERTIES = Set.of(
            "powered",
            "power",
            "lit",
            "open",
            "enabled",
            "locked",
            "triggered",
            "extended"
    );

    void propagate(ServerLevel level, BlockPos pos, BlockState currentState, BlockState targetState) {
        if (level == null || pos == null || !this.requiresPropagation(currentState, targetState)) {
            return;
        }

        Block sourceBlock = this.sourceBlock(currentState, targetState);
        for (BlockPos updatePos : this.updatePositions(pos, currentState, targetState)) {
            level.updateNeighborsAt(updatePos, sourceBlock);
        }
    }

    boolean requiresPropagation(BlockState currentState, BlockState targetState) {
        if (Objects.equals(currentState, targetState)) {
            return false;
        }
        return this.hasRedstoneProperty(currentState) || this.hasRedstoneProperty(targetState);
    }

    Set<BlockPos> updatePositions(BlockPos pos, BlockState currentState, BlockState targetState) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (pos == null) {
            return positions;
        }
        positions.add(pos.immutable());
        this.attachedNeighbor(pos, currentState).ifPresent(positions::add);
        this.attachedNeighbor(pos, targetState).ifPresent(positions::add);
        return Set.copyOf(positions);
    }

    private boolean hasRedstoneProperty(BlockState state) {
        if (state == null) {
            return false;
        }
        for (Property<?> property : state.getProperties()) {
            if (REDSTONE_PROPERTIES.contains(property.getName())) {
                return true;
            }
        }
        return false;
    }

    private Optional<BlockPos> attachedNeighbor(BlockPos pos, BlockState state) {
        if (pos == null || state == null || !this.hasProperty(state, "face")) {
            return Optional.empty();
        }

        String face = this.propertyValue(state, "face");
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

    private String propertyValue(BlockState state, String propertyName) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return String.valueOf(state.getValue(property)).toLowerCase(Locale.ROOT);
            }
        }
        return "";
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
