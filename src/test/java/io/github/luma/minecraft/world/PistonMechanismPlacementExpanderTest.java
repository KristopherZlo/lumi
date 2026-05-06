package io.github.luma.minecraft.world;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PistonMechanismPlacementExpanderTest {

    private final PistonMechanismPlacementExpander expander = new PistonMechanismPlacementExpander();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void extendedBaseAddsSettledHeadCompanion() {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedBlockPlacement> expanded = this.expander.expandTargets(List.of(
                new PreparedBlockPlacement(base, extended, null)
        ));

        assertEquals(2, expanded.size());
        BlockState head = stateAt(expanded, base.east());
        assertTrue(head.is(Blocks.PISTON_HEAD));
        assertEquals(Direction.EAST, head.getValue(PistonHeadBlock.FACING));
        assertEquals(PistonType.DEFAULT, head.getValue(PistonHeadBlock.TYPE));
    }

    @Test
    void retractingPreviouslyExtendedBaseClearsOldHeadWhenHeadWasNotExplicit() {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);
        BlockState retracted = extended.setValue(PistonBaseBlock.EXTENDED, false);

        List<PreparedBlockPlacement> expanded = this.expander.expandChanges(List.of(
                new PistonMechanismPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(base, retracted, null),
                        extended
                )
        ));

        assertEquals(2, expanded.size());
        assertTrue(stateAt(expanded, base.east()).isAir());
    }

    @Test
    void explicitHeadPlacementWinsOverGeneratedCompanion() {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedBlockPlacement> expanded = this.expander.expandChanges(List.of(
                new PistonMechanismPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(base, extended, null),
                        extended.setValue(PistonBaseBlock.EXTENDED, false)
                ),
                new PistonMechanismPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(base.east(), Blocks.OAK_PLANKS.defaultBlockState(), null),
                        Blocks.PISTON_HEAD.defaultBlockState()
                )
        ));

        assertEquals(2, expanded.size());
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), stateAt(expanded, base.east()));
    }

    @Test
    void generatedHeadOverridesExplicitAirAtHeadPosition() {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedBlockPlacement> expanded = this.expander.expandChanges(List.of(
                new PistonMechanismPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(base, extended, null),
                        extended.setValue(PistonBaseBlock.EXTENDED, false)
                ),
                new PistonMechanismPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(base.east(), Blocks.AIR.defaultBlockState(), null),
                        Blocks.MOVING_PISTON.defaultBlockState()
                )
        ));

        assertEquals(2, expanded.size());
        assertTrue(stateAt(expanded, base.east()).is(Blocks.PISTON_HEAD));
    }

    @Test
    void generatedHeadIsAppliedBeforeBase() {
        BlockPos base = new BlockPos(0, 64, 0);
        BlockState extended = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST)
                .setValue(PistonBaseBlock.EXTENDED, true);

        List<PreparedBlockPlacement> expanded = this.expander.expandTargets(List.of(
                new PreparedBlockPlacement(base, extended, null)
        ));

        assertTrue(expanded.get(0).state().is(Blocks.PISTON_HEAD));
        assertTrue(expanded.get(1).state().is(Blocks.PISTON));
    }

    private static BlockState stateAt(List<PreparedBlockPlacement> placements, BlockPos pos) {
        for (PreparedBlockPlacement placement : placements) {
            if (placement.pos().equals(pos)) {
                return placement.state();
            }
        }
        return Blocks.AIR.defaultBlockState();
    }
}
