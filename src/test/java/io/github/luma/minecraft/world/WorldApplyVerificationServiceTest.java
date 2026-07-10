package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.ChunkPoint;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyVerificationServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void verifyGroupsMismatchesIntoRepairSections() {
        WorldApplyVerificationService service = new WorldApplyVerificationService();
        BlockPos matched = new BlockPos(1, 64, 1);
        BlockPos mismatched = new BlockPos(2, 65, 2);
        BlockPos skipped = new BlockPos(3, 66, 3);

        WorldApplyVerificationResult result = service.verify(batch(List.of(
                new PreparedBlockPlacement(matched, Blocks.STONE.defaultBlockState(), null),
                new PreparedBlockPlacement(mismatched, Blocks.REDSTONE_WIRE.defaultBlockState(), null),
                new PreparedBlockPlacement(skipped, null, null)
        )), placement -> placement.pos().equals(mismatched));

        assertEquals(1, result.matched());
        assertEquals(1, result.mismatched());
        assertEquals(0, result.repaired());
        assertEquals(1, result.skipped());
        assertTrue(result.hasRepairs());
        assertEquals(1, result.repairSections().size());
        assertEquals(4, result.repairSections().getFirst().sectionY());
        assertEquals(1, result.repairSections().getFirst().placementCount());
        assertTrue(result.repairSections().getFirst().changedCells().get(SectionChangeMask.localIndex(2, 65, 2)));
    }

    @Test
    void verifyKeepsLatestPlacementPerPosition() {
        WorldApplyVerificationService service = new WorldApplyVerificationService();
        BlockPos pos = new BlockPos(5, 64, 5);

        WorldApplyVerificationResult result = service.verify(batch(List.of(
                new PreparedBlockPlacement(pos, Blocks.STONE.defaultBlockState(), null),
                new PreparedBlockPlacement(pos, Blocks.DIRT.defaultBlockState(), null)
        )), placement -> placement.state().is(Blocks.DIRT));

        assertEquals(0, result.matched());
        assertEquals(1, result.mismatched());
        assertEquals(1, result.repairSections().getFirst().placementCount());
        assertTrue(result.repairSections().getFirst().placements().getFirst().state().is(Blocks.DIRT));

        WorldApplyVerificationResult repaired = result.withRepairOutcome(1, 0);
        assertEquals(1, repaired.repaired());
        assertEquals(0, repaired.skipped());
        assertFalse(repaired.hasRepairs());
    }

    @Test
    void verificationResumesAcrossDeadlines() {
        AtomicLong clock = new AtomicLong();
        WorldApplyVerificationService service = new WorldApplyVerificationService(clock::getAndIncrement);
        WorldApplyVerificationService.Verification verification = service.begin(batch(List.of(
                new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.STONE.defaultBlockState(), null),
                new PreparedBlockPlacement(new BlockPos(2, 64, 2), Blocks.DIRT.defaultBlockState(), null),
                new PreparedBlockPlacement(new BlockPos(3, 64, 3), Blocks.GLASS.defaultBlockState(), null)
        )), placement -> placement.state().is(Blocks.DIRT));

        WorldApplyVerificationResult result = null;
        int slices = 0;
        while (result == null) {
            result = verification.advance(clock.get() + 2);
            slices += 1;
        }

        assertTrue(slices > 1);
        assertEquals(2, result.matched());
        assertEquals(1, result.mismatched());
    }

    private static ChunkBatch batch(List<PreparedBlockPlacement> placements) {
        BitSet changedCells = new BitSet(SectionChangeMask.ENTRY_COUNT);
        for (PreparedBlockPlacement placement : placements) {
            changedCells.set(SectionChangeMask.localIndex(
                    placement.pos().getX(),
                    placement.pos().getY(),
                    placement.pos().getZ()
            ));
        }
        return new ChunkBatch(
                new ChunkPoint(0, 0),
                Map.of(4, new SectionBatch(4, changedCells, placements)),
                Map.of(),
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }
}
