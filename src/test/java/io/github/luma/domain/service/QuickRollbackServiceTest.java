package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.minecraft.world.MechanismReplayScope;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickRollbackServiceTest {

    @Test
    void fullQuickRollbackUsesMechanismHaloPositions() {
        QuickRollbackService service = new QuickRollbackService();
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(1, 64, 1), new BlockPoint(2, 64, 1)),
                List.of()
        );

        List<BlockPoint> positions = service.mechanismReconciliationPositions(scope, null);

        assertEquals(List.of(new BlockPoint(1, 64, 1), new BlockPoint(2, 64, 1)), positions);
    }

    @Test
    void selectedQuickRollbackNeverWritesOutsideSelection() {
        QuickRollbackService service = new QuickRollbackService();
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(1, 64, 1), new BlockPoint(8, 64, 1)),
                List.of()
        );

        List<BlockPoint> positions = service.mechanismReconciliationPositions(
                scope,
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 80, 5))
        );

        assertTrue(positions.contains(new BlockPoint(1, 64, 1)));
        assertFalse(positions.contains(new BlockPoint(8, 64, 1)));
    }
}
