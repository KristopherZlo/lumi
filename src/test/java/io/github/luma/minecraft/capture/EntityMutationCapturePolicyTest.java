package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void playerCapturesAnyNonPlayerEntity() {
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:armor_stand", "00000000-0000-0000-0000-000000000041", 1.0D)
        ).isPresent());
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:zombie", "00000000-0000-0000-0000-000000000042", 1.0D)
        ).isPresent());
        assertTrue(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:item", "00000000-0000-0000-0000-000000000045", 1.0D)
        ).isPresent());
        assertFalse(this.policy.capture(
                WorldMutationSource.PLAYER,
                null,
                entity("minecraft:player", "00000000-0000-0000-0000-000000000046", 1.0D)
        ).isPresent());
    }

    @Test
    void playerEntityRemovalKeepsFullOldPayloadForReplay() {
        EntityPayload cow = entityWithVariant(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000047",
                1.0D,
                "minecraft:cold"
        );

        var captured = this.policy.capture(WorldMutationSource.PLAYER, cow, null);

        assertTrue(captured.isPresent());
        assertEquals("minecraft:cow", captured.get().entityType());
        assertEquals("minecraft:cold", captured.get().oldValue().copyTag().getString("variant").orElse(""));
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
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:player"));
    }

    @Test
    void inspectionKeepsExplicitRootAndExternalToolEntities() {
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:armor_stand"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:zombie"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.ENTITY, "minecraft:item"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.AXIOM, "minecraft:zombie"));
    }

    @Test
    void blockUpdateInspectionKeepsMechanismEntitiesOnly() {
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:minecart"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:chest_minecart"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:arrow"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:firework_rocket"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:zombie"));
    }

    @Test
    void externalToolFallbackInspectsBuilderRelevantEntitiesOnly() {
        assertTrue(this.policy.shouldInspectExternalToolFallback("minecraft:armor_stand"));
        assertTrue(this.policy.shouldInspectExternalToolFallback("minecraft:block_display"));
        assertFalse(this.policy.shouldInspectExternalToolFallback("minecraft:zombie"));
        assertFalse(this.policy.shouldInspectExternalToolFallback("minecraft:item"));
    }

    @Test
    void itemDropsAreUndoOnlyForExplosionAndFluidSources() {
        EntityPayload item = entity("minecraft:item", "00000000-0000-0000-0000-000000000044", 1.0D);

        assertFalse(this.policy.capture(WorldMutationSource.EXPLOSION, null, item).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.EXPLOSION, null, item).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.FLUID, null, item).isPresent());
        assertFalse(this.policy.captureUndoOnly(WorldMutationSource.PLAYER, null, item).isPresent());
    }

    @Test
    void primedTntSpawnIsUndoOnlyForExplosiveSources() {
        EntityPayload primedTnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000048", 1.0D);

        assertFalse(this.policy.capture(WorldMutationSource.EXPLOSIVE, null, primedTnt).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.EXPLOSIVE, null, primedTnt).isPresent());
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

    private static EntityPayload entityWithVariant(String type, String uuid, double x, String variant) {
        CompoundTag tag = entity(type, uuid, x).copyTag();
        tag.putString("variant", variant);
        return new EntityPayload(tag);
    }
}
