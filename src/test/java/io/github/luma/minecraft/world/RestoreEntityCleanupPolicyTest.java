package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreEntityCleanupPolicyTest {

    private static final String ITEM_ID = "00000000-0000-0000-0000-000000000070";
    private static final String PAINTING_ID = "00000000-0000-0000-0000-000000000071";

    private final RestoreEntityCleanupPolicy policy = new RestoreEntityCleanupPolicy();

    @Test
    void authoritativeReplacementScansTransientItemsAsExtraEntities() {
        assertTrue(this.policy.shouldInspectExtraEntityType("minecraft:item"));
        assertTrue(this.policy.shouldInspectExtraEntityType("minecraft:cow"));
        assertTrue(this.policy.shouldInspectExtraEntityType("minecraft:block_display"));
        assertFalse(this.policy.shouldInspectExtraEntityType("minecraft:player"));
        assertFalse(this.policy.shouldInspectExtraEntityType(""));
    }

    @Test
    void transientItemsInAuthoritativeChunkAreRemovedWhenMissingFromTarget() {
        EntityPayload item = entity("minecraft:item", ITEM_ID, 1.25D);

        assertTrue(this.policy.shouldRemoveExtraEntity(item, new ChunkPoint(0, 0), Set.of()));
        assertFalse(this.policy.shouldRemoveExtraEntity(item, new ChunkPoint(0, 0), Set.of(ITEM_ID)));
        assertFalse(this.policy.shouldRemoveExtraEntity(item, new ChunkPoint(1, 0), Set.of()));
    }

    @Test
    void placedEntitiesStillUseHistoryAnchorForChunkOwnership() {
        CompoundTag tag = entity("minecraft:painting", PAINTING_ID, 1.25D).copyTag();
        tag.putInt("TileX", 32);
        tag.putInt("TileY", 64);
        tag.putInt("TileZ", 1);
        EntityPayload painting = new EntityPayload(tag);

        assertTrue(this.policy.shouldRemoveExtraEntity(painting, new ChunkPoint(2, 0), Set.of()));
        assertFalse(this.policy.shouldRemoveExtraEntity(painting, new ChunkPoint(0, 0), Set.of()));
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
