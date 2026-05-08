package io.github.luma.minecraft.capture;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PistonMovementBaselineCaptureServiceTest {

    private final PistonMovementBaselineCaptureService service = PistonMovementBaselineCaptureService.getInstance();

    @Test
    void extendingMovementEnvelopeIncludesMovedSourcesAndDestinations() {
        BlockPos piston = new BlockPos(0, 64, 0);
        BlockPos firstMoved = piston.east();
        BlockPos secondMoved = piston.east(2);

        Set<BlockPos> positions = this.service.affectedPositions(
                piston,
                Direction.EAST,
                true,
                List.of(firstMoved, secondMoved),
                List.of()
        );

        assertTrue(positions.contains(piston));
        assertTrue(positions.contains(firstMoved));
        assertTrue(positions.contains(secondMoved));
        assertTrue(positions.contains(piston.east(3)));
        assertEquals(4, positions.size());
    }

    @Test
    void retractingMovementEnvelopeIncludesPulledSourceAndHomeCell() {
        BlockPos piston = new BlockPos(0, 64, 0);
        BlockPos pulledBlock = piston.east(2);

        Set<BlockPos> positions = this.service.affectedPositions(
                piston,
                Direction.EAST,
                false,
                List.of(pulledBlock),
                List.of()
        );

        assertTrue(positions.contains(piston));
        assertTrue(positions.contains(piston.east()));
        assertTrue(positions.contains(pulledBlock));
        assertEquals(3, positions.size());
    }

    @Test
    void movementEnvelopeIncludesDestroyedBlocks() {
        BlockPos piston = new BlockPos(0, 64, 0);
        BlockPos destroyed = piston.east(12);

        Set<BlockPos> positions = this.service.affectedPositions(
                piston,
                Direction.EAST,
                true,
                List.of(),
                List.of(destroyed)
        );

        assertTrue(positions.contains(destroyed));
    }
}
