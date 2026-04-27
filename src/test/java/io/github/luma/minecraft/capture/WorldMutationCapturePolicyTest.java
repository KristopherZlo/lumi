package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationCapturePolicyTest {

    private static final BlockPos POS = new BlockPos(1, 64, 1);

    private final WorldMutationCapturePolicy policy = new WorldMutationCapturePolicy();

    @Test
    void rejectsPistonSourceMutations() {
        assertTrue(this.policy.capture(
                WorldMutationSource.PISTON,
                POS,
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null,
                null
        ).isEmpty());
    }

    @Test
    void rejectsTransientPistonBlocksFromExplicitSources() {
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                POS,
                Blocks.AIR.defaultBlockState(),
                Blocks.PISTON_HEAD.defaultBlockState(),
                null,
                null
        ).isEmpty());
    }

    @Test
    void rejectsRuntimeOnlyRedstoneStateFlips() {
        BlockState offLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", false);
        BlockState onLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", true);

        assertTrue(this.policy.capture(WorldMutationSource.PLAYER, POS, offLever, onLever, null, null).isEmpty());
    }

    @Test
    void keepsStructuralPlacementOfRedstoneComponents() {
        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                WorldMutationSource.PLAYER,
                POS,
                Blocks.AIR.defaultBlockState(),
                Blocks.LEVER.defaultBlockState(),
                null,
                null
        );

        assertTrue(mutation.isPresent());
        assertEquals("minecraft:lever", mutation.get().change().newValue().blockId());
    }

    @Test
    void storesExplicitlyPlacedPistonsAsRetracted() {
        BlockState extendedPiston = withProperty(Blocks.PISTON.defaultBlockState(), "extended", true);

        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                WorldMutationSource.PLAYER,
                POS,
                Blocks.AIR.defaultBlockState(),
                extendedPiston,
                null,
                null
        );

        assertTrue(mutation.isPresent());
        assertEquals("minecraft:piston", mutation.get().change().newValue().blockId());
        assertFalse((Boolean) propertyValue(mutation.get().newState(), "extended"));
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
