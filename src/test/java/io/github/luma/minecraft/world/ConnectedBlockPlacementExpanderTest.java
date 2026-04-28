package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedBlockPlacementExpanderTest {

    private final ConnectedBlockPlacementExpander expander = new ConnectedBlockPlacementExpander();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void targetBedFootAddsMissingHeadAcrossChunkBoundary() {
        BlockPos foot = new BlockPos(15, 64, 1);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.FACING, Direction.EAST);

        List<PreparedBlockPlacement> expanded = this.expander.expandTargets(List.of(
                new PreparedBlockPlacement(foot, footState, null)
        ));

        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = this.expander.groupByChunk(expanded);
        assertTrue(grouped.containsKey(new ChunkPoint(0, 0)));
        assertTrue(grouped.containsKey(new ChunkPoint(1, 0)));
        PreparedBlockPlacement head = grouped.get(new ChunkPoint(1, 0)).getFirst();
        assertEquals(new BlockPos(16, 64, 1), head.pos());
        assertEquals(BedPart.HEAD, head.state().getValue(BedBlock.PART));
    }

    @Test
    void removingBedFootAddsAirForMissingHead() {
        BlockPos foot = new BlockPos(1, 64, 1);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.FACING, Direction.SOUTH);

        List<PreparedBlockPlacement> expanded = this.expander.expandChanges(List.of(
                new ConnectedBlockPlacementExpander.ChangePlacement(
                        new PreparedBlockPlacement(foot, Blocks.AIR.defaultBlockState(), null),
                        footState
                )
        ));

        assertEquals(2, expanded.size());
        PreparedBlockPlacement headRemoval = expanded.stream()
                .filter(placement -> placement.pos().equals(new BlockPos(1, 64, 2)))
                .findFirst()
                .orElseThrow();
        assertTrue(headRemoval.state().isAir());
    }

    @Test
    void explicitDoorUpperIsNotOverwrittenByExpansion() {
        BlockPos lower = new BlockPos(3, 64, 3);
        BlockState lowerState = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upperState = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.OPEN, true);

        List<PreparedBlockPlacement> expanded = this.expander.expandTargets(List.of(
                new PreparedBlockPlacement(lower, lowerState, null),
                new PreparedBlockPlacement(lower.above(), upperState, null)
        ));

        assertEquals(2, expanded.size());
        PreparedBlockPlacement upper = expanded.stream()
                .filter(placement -> placement.pos().equals(lower.above()))
                .findFirst()
                .orElseThrow();
        assertTrue(upper.state().getValue(DoorBlock.OPEN));
    }
}
