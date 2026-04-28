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
    void externalToolsCaptureAnyPersistentEntityDiff() {
        assertTrue(this.policy.capture(
                WorldMutationSource.AXIOM,
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000040", 1.0D),
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000040", 2.0D)
        ).isPresent());
    }

    @Test
    void playerCapturesBuilderRelevantEntitiesOnly() {
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:armor_stand", "00000000-0000-0000-0000-000000000041", 1.0D)
        ).isPresent());
        assertFalse(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000042", 1.0D)
        ).isPresent());
    }

    @Test
    void systemSourceDoesNotCaptureChunkLoadNoise() {
        assertFalse(this.policy.capture(
                WorldMutationSource.SYSTEM,
                null,
                entity("minecraft:armor_stand", "00000000-0000-0000-0000-000000000043", 1.0D)
        ).isPresent());
    }

    @Test
    void inspectionSkipsSourcesThatCanNeverRecordEntityHistory() {
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.FALLING_BLOCK, "minecraft:falling_block"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.MOB, "minecraft:zombie"));
    }

    @Test
    void inspectionKeepsPlayerBuilderEntitiesAndExternalToolEntities() {
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:armor_stand"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:zombie"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.AXIOM, "minecraft:zombie"));
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
