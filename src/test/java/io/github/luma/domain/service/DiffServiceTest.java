package io.github.luma.domain.service;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionDiff;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

    private final DiffService diffService = new DiffService();

    @Test
    void extractBlockIdReadsStateName() {
        String blockId = this.diffService.extractBlockId("{Name:\"minecraft:stone\",Properties:{axis:\"y\"}}");

        assertEquals("minecraft:stone", blockId);
    }

    @Test
    void extractBlockIdFallsBackForBlankAndUnknown() {
        assertEquals("minecraft:air", this.diffService.extractBlockId(""));
        assertEquals("minecraft:unknown", this.diffService.extractBlockId("{foo:\"bar\"}"));
    }

    @Test
    void statesEqualUsesStructuredPayloads() {
        StatePayload left = payload("minecraft:stone");
        StatePayload right = payload("minecraft:stone");
        StatePayload changed = payload("minecraft:dirt");

        assertTrue(this.diffService.statesEqual(left, right));
        assertFalse(this.diffService.statesEqual(left, changed));
    }

    @Test
    void classifyStateChangeUsesBlockIdsWithoutSnbtRoundTrip() {
        StatePayload air = payload("minecraft:air");
        StatePayload stone = payload("minecraft:stone");
        StatePayload dirt = payload("minecraft:dirt");

        assertEquals(ChangeType.ADDED, this.diffService.classifyStateChange(air, stone));
        assertEquals(ChangeType.REMOVED, this.diffService.classifyStateChange(stone, air));
        assertEquals(ChangeType.CHANGED, this.diffService.classifyStateChange(stone, dirt));
    }

    @Test
    void applyDraftMergesEntityDiffsIntoCurrentStateDiff() {
        String entityId = "00000000-0000-0000-0000-000000000001";
        VersionDiff baseDiff = new VersionDiff(
                "v0001",
                "v0002",
                List.of(),
                1,
                List.of(entityChange(entityId, 1.0D, 2.0D))
        );

        VersionDiff currentDiff = this.diffService.applyDraft(
                baseDiff,
                List.of(),
                List.of(entityChange(entityId, 2.0D, 3.0D))
        );

        assertEquals("current", currentDiff.rightVersionId());
        assertEquals(1, currentDiff.changedEntityCount());
        assertEquals(1.0D, x(currentDiff.changedEntities().getFirst().oldValue()));
        assertEquals(3.0D, x(currentDiff.changedEntities().getFirst().newValue()));
    }

    private static StatePayload payload(String blockId) {
        CompoundTag stateTag = new CompoundTag();
        stateTag.putString("Name", blockId);
        return new StatePayload(stateTag, null);
    }

    private static StoredEntityChange entityChange(String entityId, double oldX, double newX) {
        return new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity(entityId, oldX),
                entity(entityId, newX)
        );
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }

    private static double x(EntityPayload payload) {
        return payload.entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D);
    }
}
