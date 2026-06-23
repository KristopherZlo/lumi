package io.github.luma.minecraft.world;

import io.github.luma.domain.model.StatePayload;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Central classification policy for redstone and mechanism states used by
 * capture scoping, exact replay, and restore reconciliation.
 */
public final class MechanismStatePolicy {

    private static final Set<String> MECHANISM_BLOCK_IDS = Set.of(
            "minecraft:redstone_wire",
            "minecraft:redstone_torch",
            "minecraft:redstone_wall_torch",
            "minecraft:redstone_block",
            "minecraft:repeater",
            "minecraft:comparator",
            "minecraft:redstone_lamp",
            "minecraft:observer",
            "minecraft:dispenser",
            "minecraft:dropper",
            "minecraft:piston",
            "minecraft:sticky_piston",
            "minecraft:piston_head",
            "minecraft:moving_piston",
            "minecraft:lever",
            "minecraft:tripwire",
            "minecraft:tripwire_hook",
            "minecraft:target"
    );
    private static final Set<String> MECHANISM_PROPERTY_NAMES = Set.of(
            "attached",
            "enabled",
            "extended",
            "in_wall",
            "lit",
            "locked",
            "open",
            "power",
            "powered",
            "triggered"
    );
    private static final Set<String> VOLATILE_REPLAY_PROPERTY_NAMES = Set.of(
            "delay",
            "east",
            "extended",
            "lit",
            "locked",
            "mode",
            "north",
            "power",
            "powered",
            "south",
            "triggered",
            "west"
    );

    public boolean isMechanismRelevant(BlockState state) {
        if (state == null) {
            return false;
        }
        return this.isRedstoneMechanism(state)
                || this.isPistonMechanismParticipant(state)
                || this.isPlayerInputControl(state)
                || this.hasAnyPropertyNamed(state, MECHANISM_PROPERTY_NAMES);
    }

    public boolean isMechanismPayload(StatePayload payload) {
        if (payload == null || payload.blockId() == null) {
            return false;
        }
        String blockId = payload.blockId().toLowerCase(Locale.ROOT);
        return MECHANISM_BLOCK_IDS.contains(blockId)
                || blockId.endsWith("_button")
                || blockId.endsWith("_pressure_plate")
                || this.hasMechanismStateProperty(payload);
    }

    public boolean isMechanismReplayRelevant(BlockState state) {
        return this.isMechanismRelevant(state) || this.shouldSuppressReplayCallbacks(state);
    }

    public boolean isPlayerInputControl(BlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        return block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof BasePressurePlateBlock
                || block instanceof TripWireBlock
                || block instanceof TripWireHookBlock;
    }

    public boolean isPistonMechanismParticipant(BlockState state) {
        return state != null
                && (state.is(Blocks.PISTON)
                || state.is(Blocks.STICKY_PISTON)
                || state.is(Blocks.PISTON_HEAD)
                || state.is(Blocks.MOVING_PISTON)
                || state.is(Blocks.OBSERVER));
    }

    public boolean shouldScopeBlockUpdate(BlockState state) {
        return this.isMechanismRelevant(state);
    }

    public boolean shouldGuardExactReplay(BlockState state) {
        if (state == null
                || state.isAir()
                || this.isPlayerInputControl(state)
                || this.isPistonMechanismParticipant(state)) {
            return false;
        }
        return this.hasAnyPropertyNamed(state, VOLATILE_REPLAY_PROPERTY_NAMES);
    }

    public boolean shouldSuppressReplayCallbacks(BlockState state) {
        return state != null
                && !state.isAir()
                && (this.shouldGuardExactReplay(state) || this.isPistonMechanismParticipant(state));
    }

    boolean signalRelevant(BlockState state) {
        return state != null && (state.isSignalSource() || state.hasAnalogOutputSignal());
    }

    boolean propertyChanged(BlockState currentState, BlockState targetState, String propertyName) {
        Optional<String> currentValue = this.stringPropertyValue(currentState, propertyName);
        Optional<String> targetValue = this.stringPropertyValue(targetState, propertyName);
        return currentValue.isPresent() && targetValue.isPresent() && !currentValue.equals(targetValue);
    }

    Optional<BlockPos> attachedNeighbor(BlockPos pos, BlockState state) {
        if (pos == null || state == null || !this.hasPropertyNamed(state, "face")) {
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

    private boolean isRedstoneMechanism(BlockState state) {
        return state != null
                && (this.signalRelevant(state)
                || state.is(Blocks.REDSTONE_WIRE)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.REPEATER)
                || state.is(Blocks.COMPARATOR)
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.DISPENSER)
                || state.is(Blocks.DROPPER));
    }

    private boolean hasMechanismStateProperty(StatePayload payload) {
        if (payload.stateTag() == null) {
            return false;
        }
        Optional<CompoundTag> properties = payload.stateTag().getCompound("Properties");
        if (properties.isEmpty()) {
            return false;
        }
        for (String propertyName : properties.orElseThrow().keySet()) {
            if (MECHANISM_PROPERTY_NAMES.contains(propertyName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyPropertyNamed(BlockState state, Set<String> propertyNames) {
        for (Property<?> property : state.getProperties()) {
            if (propertyNames.contains(property.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPropertyNamed(BlockState state, String propertyName) {
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
}
