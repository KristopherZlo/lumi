package io.github.luma.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BuilderChangeSurfacePolicyTest {

    private final BuilderChangeSurfacePolicy policy = new BuilderChangeSurfacePolicy();

    @Test
    void filtersHiddenAndNullChangesForBuilderSurfaces() {
        StoredBlockChange visible = change(1, false);
        StoredBlockChange hidden = change(2, true);

        assertEquals(List.of(visible), this.policy.visibleBlockChanges(List.of(hidden, visible)));
    }

    @Test
    void reportsHiddenOnlyChangesAsEmptyBuilderSurface() {
        assertFalse(this.policy.hasVisibleBlockChanges(List.of(change(1, true))));
        assertEquals(0, this.policy.visibleBlockChangeCount(List.of(change(1, true))));
    }

    @Test
    void reportsVisibleChangesForBuilderSurface() {
        assertTrue(this.policy.includes(change(1, false)));
        assertFalse(this.policy.includes(change(1, true)));
        assertFalse(this.policy.includes(null));
    }

    private static StoredBlockChange change(int x, boolean hidden) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload("minecraft:stone"),
                payload("minecraft:oak_planks"),
                hidden
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
