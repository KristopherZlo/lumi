package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMutationCapturePolicyTest {

    private final EntityMutationCapturePolicy policy = new EntityMutationCapturePolicy();

    @Test
    void durableHistoryCapturesPlacedEntitiesButNotSimulationEntities() {
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:armor_stand", "00000000-0000-0000-0000-000000000041")
        ).isPresent());
        assertFalse(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000042")
        ).isPresent());
    }

    @Test
    void externalToolsCapturePersistentEntityDiffs() {
        assertTrue(this.policy.capture(
                WorldMutationSource.AXIOM,
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000040"),
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000040")
        ).isEmpty(), "unchanged entities remain no-ops");
    }

    @Test
    void liveUndoCapturesTransientEntitiesWithoutPersistingThem() {
        EntityPayload item = entity("minecraft:item", "00000000-0000-0000-0000-000000000043");
        EntityPayload tnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000044");

        assertTrue(this.policy.captureUndoRedo(WorldMutationSource.PLAYER, null, item).isPresent());
        assertTrue(this.policy.captureUndoRedo(WorldMutationSource.EXPLOSIVE, null, tnt).isPresent());
        assertFalse(this.policy.capture(WorldMutationSource.PLAYER, null, item).isPresent());
        assertFalse(this.policy.capture(WorldMutationSource.EXPLOSIVE, null, tnt).isPresent());
    }

    @Test
    void systemAndPlayerEntitiesAreNeverCaptured() {
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.SYSTEM, "minecraft:armor_stand"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:player"));
    }

    private static EntityPayload entity(String type, String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(1.0D));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
