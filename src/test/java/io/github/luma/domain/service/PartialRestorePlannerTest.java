package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialRestorePlannerTest {

    private final PartialRestorePlanner planner = new PartialRestorePlanner();

    @Test
    void filtersLineageChangesToBounds() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 5, 5));
        StoredBlockChange inside = change(1, 1, 1, "minecraft:stone", "minecraft:oak_planks");
        StoredBlockChange outside = change(8, 1, 1, "minecraft:dirt", "minecraft:glass");

        List<StoredBlockChange> planned = this.planner.plan(List.of(), List.of(inside, outside), false, bounds);

        assertEquals(1, planned.size());
        assertEquals(new BlockPoint(1, 1, 1), planned.getFirst().pos());
        assertEquals("minecraft:oak_planks", planned.getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", planned.getFirst().newValue().blockId());
    }

    @Test
    void keepsPendingDraftCurrentStateWhenPendingAndLineageOverlap() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 5, 5));
        StoredBlockChange pending = change(1, 1, 1, "minecraft:oak_planks", "minecraft:diamond_block");
        StoredBlockChange lineage = change(1, 1, 1, "minecraft:stone", "minecraft:oak_planks");

        List<StoredBlockChange> planned = this.planner.plan(List.of(pending), List.of(lineage), false, bounds);

        assertEquals(1, planned.size());
        assertEquals("minecraft:diamond_block", planned.getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", planned.getFirst().newValue().blockId());
    }

    @Test
    void collapsesForwardLineageChangesToFinalTarget() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 5, 5));
        StoredBlockChange first = change(1, 1, 1, "minecraft:stone", "minecraft:oak_planks");
        StoredBlockChange second = change(1, 1, 1, "minecraft:oak_planks", "minecraft:glass");

        List<StoredBlockChange> planned = this.planner.plan(List.of(), List.of(first, second), true, bounds);

        assertEquals(1, planned.size());
        assertEquals("minecraft:stone", planned.getFirst().oldValue().blockId());
        assertEquals("minecraft:glass", planned.getFirst().newValue().blockId());
    }

    @Test
    void outsideSelectionModeKeepsSelectedBlocksUntouched() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 5, 5));
        StoredBlockChange inside = change(1, 1, 1, "minecraft:stone", "minecraft:oak_planks");
        StoredBlockChange outside = change(8, 1, 1, "minecraft:dirt", "minecraft:glass");

        List<StoredBlockChange> planned = this.planner.plan(
                List.of(),
                List.of(outside),
                List.of(inside),
                bounds,
                PartialRestoreMode.OUTSIDE_SELECTED_AREA
        );

        assertEquals(1, planned.size());
        assertEquals(new BlockPoint(8, 1, 1), planned.getFirst().pos());
        assertEquals("minecraft:glass", planned.getFirst().oldValue().blockId());
        assertEquals("minecraft:dirt", planned.getFirst().newValue().blockId());
    }

    @Test
    void divergentPlanAppliesReverseThenForwardInsideSelection() {
        Bounds3i bounds = new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 5, 5));
        StoredBlockChange reverse = change(1, 1, 1, "minecraft:stone", "minecraft:oak_planks");
        StoredBlockChange forward = change(1, 1, 1, "minecraft:stone", "minecraft:glass");

        List<StoredBlockChange> planned = this.planner.plan(
                List.of(),
                List.of(reverse),
                List.of(forward),
                bounds,
                PartialRestoreMode.SELECTED_AREA
        );

        assertEquals(1, planned.size());
        assertEquals("minecraft:oak_planks", planned.getFirst().oldValue().blockId());
        assertEquals("minecraft:glass", planned.getFirst().newValue().blockId());
    }

    private static StoredBlockChange change(int x, int y, int z, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, y, z),
                payload(oldBlock),
                payload(newBlock)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
