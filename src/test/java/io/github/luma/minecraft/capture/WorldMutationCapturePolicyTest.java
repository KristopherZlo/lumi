package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.WorldMutationSource;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMutationCapturePolicyTest {

    private static final BlockPos POS = new BlockPos(1, 64, 1);

    private final WorldMutationCapturePolicy policy = new WorldMutationCapturePolicy();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsPistonSourceMutations() {
        assertEquals(
                WorldMutationCapturePolicy.CaptureDecision.DEFER_TO_STABILIZATION,
                this.policy.evaluate(
                        WorldMutationSource.PISTON,
                        POS,
                        Blocks.STONE.defaultBlockState(),
                        Blocks.AIR.defaultBlockState(),
                        null,
                        null
                ).decision()
        );
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
    void capturesSettledPistonHeadFromExplicitSources() {
        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                WorldMutationSource.PLAYER,
                POS,
                Blocks.AIR.defaultBlockState(),
                Blocks.PISTON_HEAD.defaultBlockState(),
                null,
                null
        );

        assertTrue(mutation.isPresent());
        assertEquals("minecraft:piston_head", mutation.get().change().newValue().blockId());
    }

    @Test
    void capturesRedstoneStateFlipsFromExplicitSources() {
        BlockState offLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", false);
        BlockState onLever = withProperty(Blocks.LEVER.defaultBlockState(), "powered", true);

        assertEquals(
                WorldMutationCapturePolicy.CaptureDecision.CAPTURED,
                this.policy.evaluate(WorldMutationSource.PLAYER, POS, offLever, onLever, null, null).decision()
        );
        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation =
                this.policy.capture(WorldMutationSource.PLAYER, POS, offLever, onLever, null, null);
        assertTrue(mutation.isPresent());
        assertTrue((Boolean) propertyValue(mutation.get().newState(), "powered"));
    }

    @Test
    void capturesRedstoneWirePowerFromExplicitSources() {
        BlockState offWire = withProperty(Blocks.REDSTONE_WIRE.defaultBlockState(), "power", 0);
        BlockState onWire = withProperty(Blocks.REDSTONE_WIRE.defaultBlockState(), "power", 15);

        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation =
                this.policy.capture(WorldMutationSource.PLAYER, POS, offWire, onWire, null, null);

        assertTrue(mutation.isPresent());
        assertEquals(15, propertyValue(mutation.get().newState(), "power"));
    }

    @Test
    void capturesRedstoneWireShapeFromExplicitSources() {
        BlockState dotWire = withProperty(Blocks.REDSTONE_WIRE.defaultBlockState(), "north", RedstoneSide.NONE);
        BlockState connectedWire = withProperty(dotWire, "north", RedstoneSide.SIDE);

        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation =
                this.policy.capture(WorldMutationSource.PLAYER, POS, dotWire, connectedWire, null, null);

        assertTrue(mutation.isPresent());
        assertEquals(RedstoneSide.SIDE, propertyValue(mutation.get().newState(), "north"));
    }

    @Test
    void capturesCausalSecondaryFalloutAsHiddenBuilderSurfaceChanges() {
        for (WorldMutationSource source : List.of(
                WorldMutationSource.EXPLOSION,
                WorldMutationSource.FLUID,
                WorldMutationSource.FIRE,
                WorldMutationSource.GROWTH,
                WorldMutationSource.FALLING_BLOCK
        )) {
            Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                    source,
                    POS,
                    Blocks.AIR.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(),
                    null,
                    null
            );

            assertTrue(mutation.isPresent(), source.name());
            assertTrue(mutation.get().change().hidden(), source.name());
        }
    }

    @Test
    void mobBreaksAreVisibleInPendingBuilderSurfaces() {
        Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                WorldMutationSource.MOB,
                POS,
                Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                null,
                null
        );

        assertTrue(mutation.isPresent());
        assertFalse(mutation.get().change().hidden());
    }

    @Test
    void keepsBuilderRootSourcesVisible() {
        for (WorldMutationSource source : List.of(
                WorldMutationSource.PLAYER,
                WorldMutationSource.EXPLOSIVE,
                WorldMutationSource.AXIOM
        )) {
            Optional<WorldMutationCapturePolicy.CapturedMutation> mutation = this.policy.capture(
                    source,
                    POS,
                    Blocks.AIR.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(),
                    null,
                    null
            );

            assertTrue(mutation.isPresent(), source.name());
            assertFalse(mutation.get().change().hidden(), source.name());
        }
    }

    @Test
    void defersBlockUpdateMutationsToStabilization() {
        assertEquals(
                WorldMutationCapturePolicy.CaptureDecision.DEFER_TO_STABILIZATION,
                this.policy.evaluate(
                        WorldMutationSource.BLOCK_UPDATE,
                        POS,
                        Blocks.STONE.defaultBlockState(),
                        Blocks.COPPER_BLOCK.defaultBlockState(),
                        null,
                        null
                ).decision()
        );
        assertTrue(this.policy.capture(
                WorldMutationSource.BLOCK_UPDATE,
                POS,
                Blocks.STONE.defaultBlockState(),
                Blocks.COPPER_BLOCK.defaultBlockState(),
                null,
                null
        ).isEmpty());
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
    void storesExplicitlyPlacedPistonsAsSettled() {
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
        assertTrue((Boolean) propertyValue(mutation.get().newState(), "extended"));
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
