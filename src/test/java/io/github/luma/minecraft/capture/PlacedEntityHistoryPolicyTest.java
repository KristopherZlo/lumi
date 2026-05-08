package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedEntityHistoryPolicyTest {

    private final PlacedEntityHistoryPolicy policy = new PlacedEntityHistoryPolicy();

    @Test
    void persistsDecorativeBuilderEntitiesOnly() {
        assertTrue(this.policy.shouldPersist("minecraft:painting"));
        assertTrue(this.policy.shouldPersist("minecraft:item_frame"));
        assertTrue(this.policy.shouldPersist("minecraft:glow_item_frame"));
        assertTrue(this.policy.shouldPersist("minecraft:armor_stand"));
        assertTrue(this.policy.shouldPersist("minecraft:block_display"));
        assertFalse(this.policy.shouldPersist("minecraft:zombie"));
        assertFalse(this.policy.shouldPersist("minecraft:player"));
    }

    @Test
    void hangingEntityAnchorDeterminesSnapshotChunk() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:painting");
        tag.putInt("TileX", 31);
        tag.putInt("TileY", 64);
        tag.putInt("TileZ", -1);
        EntityPayload payload = new EntityPayload(tag);

        assertEquals(31, this.policy.historyBlockPos(payload).getX());
        assertEquals(-1, this.policy.historyBlockPos(payload).getZ());
        assertTrue(this.policy.belongsToChunk(payload, 1, -1));
        assertFalse(this.policy.belongsToChunk(payload, 0, 0));
    }

    @Test
    void displayEntityBlockPosTagCanAnchorFuturePlacedEntities() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putIntArray("block_pos", new int[] {32, 70, 48});
        EntityPayload payload = new EntityPayload(tag);

        assertEquals(32, this.policy.historyBlockPos(payload).getX());
        assertEquals(48, this.policy.historyBlockPos(payload).getZ());
        assertTrue(this.policy.belongsToChunk(payload, 2, 3));
    }
}
