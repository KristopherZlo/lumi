package io.github.luma.minecraft.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentBlockStatePolicyTest {

    private final PersistentBlockStatePolicy policy = new PersistentBlockStatePolicy();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void normalizesOnlyMovingPistonBlocksToAir() {
        assertFalse(this.policy.normalizeState(Blocks.PISTON_HEAD.defaultBlockState()).isAir());
        assertTrue(this.policy.normalizeState(Blocks.MOVING_PISTON.defaultBlockState()).isAir());
    }

    @Test
    void keepsSettledPistonBaseState() {
        BlockState extendedPiston = withProperty(Blocks.PISTON.defaultBlockState(), "extended", true);

        BlockState normalized = this.policy.normalizeState(extendedPiston);

        assertTrue((Boolean) propertyValue(normalized, "extended"));
    }

    @Test
    void keepsRedstoneStateChangesPersistent() {
        BlockState offLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", false);
        BlockState onLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", true);

        assertFalse(this.policy.normalizeState(offLever).equals(this.policy.normalizeState(onLever)));
    }

    @Test
    void dropsBlockEntityTagWhenNormalizedStateCannotHostIt() {
        net.minecraft.nbt.CompoundTag chestTag = new net.minecraft.nbt.CompoundTag();
        chestTag.putString("id", "minecraft:chest");

        PersistentBlockStatePolicy.PersistentBlockState normalized =
                this.policy.normalize(Blocks.STONE.defaultBlockState(), chestTag);

        assertNull(normalized.blockEntityTag());
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
