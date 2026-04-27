package io.github.luma.minecraft.world;

import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Normalizes Minecraft runtime-only block states before Lumi stores or reapplies
 * history data.
 */
public final class PersistentBlockStatePolicy {

    private static final Set<String> RUNTIME_PROPERTY_NAMES = Set.of(
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

    public PersistentBlockState normalize(BlockState state, CompoundTag blockEntityTag) {
        BlockState normalizedState = this.normalizeState(state);
        CompoundTag normalizedBlockEntity = normalizedState.isAir() || blockEntityTag == null
                ? null
                : blockEntityTag.copy();
        return new PersistentBlockState(normalizedState, normalizedBlockEntity);
    }

    public BlockState normalizeState(BlockState state) {
        if (state == null || this.isTransientPistonState(state)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (this.isPistonBaseState(state)) {
            return this.withBooleanProperty(state, "extended", false);
        }
        return state;
    }

    public boolean isTransientPistonState(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON);
    }

    public boolean isRuntimeOnlyStateChange(BlockState oldState, BlockState newState) {
        if (oldState == null || newState == null || oldState.equals(newState)) {
            return false;
        }
        if (oldState.getBlock() != newState.getBlock()) {
            return false;
        }

        boolean changed = false;
        for (Property<?> property : oldState.getProperties()) {
            if (!newState.hasProperty(property)) {
                return false;
            }
            Object oldValue = oldState.getValue(property);
            Object newValue = newState.getValue(property);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (!RUNTIME_PROPERTY_NAMES.contains(property.getName())) {
                return false;
            }
            changed = true;
        }
        return changed;
    }

    private boolean isPistonBaseState(BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState withBooleanProperty(BlockState state, String propertyName, boolean value) {
        for (Property<?> property : state.getProperties()) {
            if (!propertyName.equals(property.getName())) {
                continue;
            }
            Object currentValue = state.getValue(property);
            if (currentValue instanceof Boolean) {
                return state.setValue((Property) property, value);
            }
        }
        return state;
    }

    public record PersistentBlockState(BlockState state, CompoundTag blockEntityTag) {
    }
}
