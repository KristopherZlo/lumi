package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.StoredEntityChange;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartialRestoreEntityPlannerTest {

    private static final Bounds3i ZONE = new Bounds3i(
            new BlockPoint(0, 60, 0),
            new BlockPoint(8, 70, 8)
    );

    private final PartialRestoreEntityPlanner planner = new PartialRestoreEntityPlanner();

    @Test
    void pendingItemDropsInsideSelectedZoneAreRemoved() {
        String dropId = "00000000-0000-0000-0000-000000000101";
        EntityPayload drop = entity("minecraft:item", dropId, 3.0D);

        List<StoredEntityChange> planned = this.planner.plan(
                List.of(new StoredEntityChange(dropId, "minecraft:item", null, drop)),
                List.of(),
                List.of(),
                ZONE,
                PartialRestoreMode.SELECTED_AREA
        );

        assertEquals(1, planned.size());
        StoredEntityChange change = planned.getFirst();
        assertEquals(dropId, change.entityId());
        assertTrue(change.isRemove());
        assertEquals(dropId, change.oldValue().entityId());
        assertNull(change.newValue());
    }

    @Test
    void pendingDestroyedEntitiesInsideSelectedZoneAreRespawned() {
        String cowId = "00000000-0000-0000-0000-000000000102";
        EntityPayload cow = entity("minecraft:cow", cowId, 4.0D);

        List<StoredEntityChange> planned = this.planner.plan(
                List.of(new StoredEntityChange(cowId, "minecraft:cow", cow, null)),
                List.of(),
                List.of(),
                ZONE,
                PartialRestoreMode.SELECTED_AREA
        );

        assertEquals(1, planned.size());
        StoredEntityChange change = planned.getFirst();
        assertEquals(cowId, change.entityId());
        assertTrue(change.isSpawn());
        assertNull(change.oldValue());
        assertEquals(cowId, change.newValue().entityId());
    }

    @Test
    void pendingDropsOutsideSelectedZoneAreIgnored() {
        String dropId = "00000000-0000-0000-0000-000000000103";

        List<StoredEntityChange> planned = this.planner.plan(
                List.of(new StoredEntityChange(dropId, "minecraft:item", null, entity("minecraft:item", dropId, 20.0D))),
                List.of(),
                List.of(),
                ZONE,
                PartialRestoreMode.SELECTED_AREA
        );

        assertTrue(planned.isEmpty());
    }

    @Test
    void pendingEntitiesMovedIntoSelectedZoneAreMovedBackOut() {
        String cowId = "00000000-0000-0000-0000-000000000105";
        EntityPayload outsideBefore = entity("minecraft:cow", cowId, 20.0D);
        EntityPayload insideNow = entity("minecraft:cow", cowId, 3.0D);

        List<StoredEntityChange> planned = this.planner.plan(
                List.of(new StoredEntityChange(cowId, "minecraft:cow", outsideBefore, insideNow)),
                List.of(),
                List.of(),
                ZONE,
                PartialRestoreMode.SELECTED_AREA
        );

        assertEquals(1, planned.size());
        StoredEntityChange change = planned.getFirst();
        assertEquals(insideNow.blockPos(), change.oldValue().blockPos());
        assertEquals(outsideBefore.blockPos(), change.newValue().blockPos());
    }

    @Test
    void zoneHardScopeStillLimitsPendingDropRemoval() {
        String dropId = "00000000-0000-0000-0000-000000000104";

        List<StoredEntityChange> planned = this.planner.plan(
                List.of(new StoredEntityChange(dropId, "minecraft:item", null, entity("minecraft:item", dropId, 3.0D))),
                List.of(),
                List.of(),
                ZONE,
                PartialRestoreMode.SELECTED_AREA,
                point -> false
        );

        assertTrue(planned.isEmpty());
    }

    private static EntityPayload entity(String type, String uuid, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
