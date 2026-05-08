package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewBoundsResolverTest {

    @Test
    void changedBlockBoundsUseTightBlockSpanInsteadOfWholeChunkColumns() {
        Bounds3i bounds = PreviewBoundsResolver.changedBlockBounds(
                List.of(
                        change(14, 90, -84),
                        change(10, 89, -85)
                ),
                null,
                -64,
                319
        );

        assertEquals(new BlockPoint(7, 87, -88), bounds.min());
        assertEquals(new BlockPoint(17, 92, -81), bounds.max());
    }

    @Test
    void changedBlockBoundsClampPaddingInsideProjectBounds() {
        Bounds3i bounds = PreviewBoundsResolver.changedBlockBounds(
                List.of(change(16, 64, 16)),
                new Bounds3i(new BlockPoint(16, 60, 16), new BlockPoint(32, 80, 32)),
                -64,
                319
        );

        assertEquals(new BlockPoint(16, 62, 16), bounds.min());
        assertEquals(new BlockPoint(19, 66, 19), bounds.max());
    }

    @Test
    void changedBlockBoundsIgnoreHiddenChanges() {
        Bounds3i bounds = PreviewBoundsResolver.changedBlockBounds(
                List.of(new StoredBlockChange(
                        new BlockPoint(100, 64, 100),
                        payload("minecraft:air"),
                        payload("minecraft:wheat"),
                        true
                )),
                null,
                -64,
                319
        );

        assertNull(bounds);
    }

    private static StoredBlockChange change(int x, int y, int z) {
        return new StoredBlockChange(
                new BlockPoint(x, y, z),
                payload("minecraft:stone"),
                payload("minecraft:air")
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
