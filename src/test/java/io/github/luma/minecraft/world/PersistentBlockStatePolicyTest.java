package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentBlockStatePolicyTest {

    private final PersistentBlockStatePolicy policy = new PersistentBlockStatePolicy();

    @Test
    void normalizesTransientPistonBlocksToAir() {
        assertTrue(this.policy.normalizeState(Blocks.PISTON_HEAD.defaultBlockState()).isAir());
        assertTrue(this.policy.normalizeState(Blocks.MOVING_PISTON.defaultBlockState()).isAir());
    }

    @Test
    void normalizesPistonBaseToRetractedState() {
        BlockState extendedPiston = withProperty(Blocks.PISTON.defaultBlockState(), "extended", true);

        BlockState normalized = this.policy.normalizeState(extendedPiston);

        assertFalse((Boolean) propertyValue(normalized, "extended"));
    }

    @Test
    void detectsRuntimeOnlyStateChanges() {
        BlockState offLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", false);
        BlockState onLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", true);

        assertTrue(this.policy.isRuntimeOnlyStateChange(offLever, onLever));
    }

    @Test
    void keepsConfigurationStateChanges() {
        BlockState defaultRepeater = Blocks.REPEATER.defaultBlockState();
        BlockState delayedRepeater = withProperty(defaultRepeater, "delay", 2);

        assertFalse(this.policy.isRuntimeOnlyStateChange(defaultRepeater, delayedRepeater));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withProperty(BlockState state, String propertyName, Comparable value) {
        for (Property<?> property : state.getProperties()) {
            if (propertyName.equals(property.getName())) {
                return state.setValue((Property) property, value);
            }
        }
        throw new IllegalArgumentException("Missing property " + propertyName + " on " + state);
    }

    private static Object propertyValue(BlockState state, String propertyName) {
        for (Property<?> property : state.getProperties()) {
            if (propertyName.equals(property.getName())) {
                return state.getValue(property);
            }
        }
        throw new IllegalArgumentException("Missing property " + propertyName + " on " + state);
    }
}
