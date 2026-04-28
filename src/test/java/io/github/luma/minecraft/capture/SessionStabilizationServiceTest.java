package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStabilizationServiceTest {

    @Test
    void reconciliationResultExposesImmutableDeltaChanges() {
        List<StoredBlockChange> deltas = new ArrayList<>();
        deltas.add(changeAt(1));

        SessionStabilizationService.ReconciliationResult result = new SessionStabilizationService.ReconciliationResult(
                1,
                1,
                1,
                0,
                1,
                false,
                true,
                deltas
        );

        deltas.clear();

        assertEquals(1, result.deltaChanges().size());
        assertTrue(result.bufferChanged());
        assertThrows(UnsupportedOperationException.class, () -> result.deltaChanges().add(changeAt(2)));
    }

    @Test
    void emptyReconciliationResultsHaveNoDeltaChanges() {
        assertTrue(SessionStabilizationService.ReconciliationResult.noOp().deltaChanges().isEmpty());
        assertTrue(SessionStabilizationService.ReconciliationResult.busy().deltaChanges().isEmpty());
    }

    private static StoredBlockChange changeAt(int x) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
