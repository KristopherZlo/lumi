package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
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
    void playerCapturesPlacedEntitiesOnlyForDurableHistory() {
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
        assertFalse(this.policy.capture(
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
    void playerTransientEntityRemovalIsUndoOnlyWithFullOldPayloadForReplay() {
        EntityPayload cow = entityWithVariant(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000047",
                1.0D,
                "minecraft:cold"
        );

        var captured = this.policy.captureUndoOnly(WorldMutationSource.PLAYER, cow, null);

        assertTrue(captured.isPresent());
        assertEquals("minecraft:cow", captured.get().entityType());
        assertEquals("minecraft:cold", captured.get().oldValue().copyTag().getString("variant").orElse(""));
    }

    @Test
    void explosiveTransientEntityRemovalIsUndoOnlyWithFullOldPayloadForReplay() {
        EntityPayload cow = entityWithVariant(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000053",
                1.0D,
                "minecraft:cold"
        );

        var captured = this.policy.captureUndoOnly(WorldMutationSource.EXPLOSIVE, cow, null);

        assertTrue(captured.isPresent());
        assertEquals("minecraft:cow", captured.get().entityType());
        assertEquals("minecraft:cold", captured.get().oldValue().copyTag().getString("variant").orElse(""));
    }

    @Test
    void playerCausedEntitySourcesRemoveTransientMobsForUndoOnlyReplay() {
        EntityPayload cow = entityWithVariant(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000055",
                1.0D,
                "minecraft:cold"
        );

        for (WorldMutationSource source : new WorldMutationSource[]{WorldMutationSource.EXPLOSION, WorldMutationSource.MOB}) {
            assertFalse(this.policy.capture(source, cow, null).isPresent());
            assertTrue(this.policy.captureUndoOnly(source, cow, null).isPresent());
        }
    }

    @Test
    void transientMobMovementIsNotUndoOnlyReplay() {
        EntityPayload oldCow = entity(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000056",
                1.0D
        );
        EntityPayload movedCow = entity(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000056",
                2.0D
        );

        assertFalse(this.policy.captureUndoOnly(WorldMutationSource.MOB, oldCow, movedCow).isPresent());
        assertFalse(this.policy.captureUndoOnly(WorldMutationSource.EXPLOSION, oldCow, movedCow).isPresent());
        assertFalse(this.policy.captureUndoOnly(WorldMutationSource.PLAYER, oldCow, movedCow).isPresent());
    }

    @Test
    void playerTransientEntityStateChangesAreUndoOnlyForLiveReplay() {
        EntityPayload oldCreeper = entityWithFlag(
                "minecraft:creeper",
                "00000000-0000-0000-0000-000000000057",
                1.0D,
                "ignited",
                false
        );
        EntityPayload ignitedCreeper = entityWithFlag(
                "minecraft:creeper",
                "00000000-0000-0000-0000-000000000057",
                1.0D,
                "ignited",
                true
        );

        assertFalse(this.policy.capture(WorldMutationSource.PLAYER, oldCreeper, ignitedCreeper).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.PLAYER, oldCreeper, ignitedCreeper).isPresent());
        assertFalse(this.policy.captureUndoOnly(WorldMutationSource.MOB, oldCreeper, ignitedCreeper).isPresent());
    }

    @Test
    void explosivePlacedEntityRemovalStaysDurable() {
        EntityPayload stand = entity(
                "minecraft:armor_stand",
                "00000000-0000-0000-0000-000000000054",
                1.0D
        );

        var captured = this.policy.capture(WorldMutationSource.EXPLOSIVE, stand, null);

        assertTrue(captured.isPresent());
        assertEquals("minecraft:armor_stand", captured.get().entityType());
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
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.PLAYER, "minecraft:zombie"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.ENTITY, "minecraft:item"));
        assertTrue(this.policy.shouldInspectMutation(WorldMutationSource.AXIOM, "minecraft:zombie"));
    }

    @Test
    void playerSpawnInspectsOrdinaryEntitiesForDurableHistory() {
        assertTrue(this.policy.shouldInspectSpawnMutation(WorldMutationSource.PLAYER, "minecraft:llama"));
        assertFalse(this.policy.shouldInspectSpawnMutation(WorldMutationSource.PLAYER, "minecraft:player"));
        assertFalse(this.policy.shouldInspectSpawnMutation(WorldMutationSource.ENTITY, "minecraft:llama"));
    }

    @Test
    void playerTransientSpawnsUseUndoOnlyInspection() {
        assertFalse(this.policy.shouldInspectSpawnMutation(WorldMutationSource.PLAYER, "minecraft:item"));
        assertFalse(this.policy.shouldInspectSpawnMutation(WorldMutationSource.PLAYER, "minecraft:tnt"));
        assertTrue(this.policy.shouldInspectUndoOnlyMutation(WorldMutationSource.PLAYER, "minecraft:item"));
        assertTrue(this.policy.shouldInspectUndoOnlyMutation(WorldMutationSource.PLAYER, "minecraft:tnt"));
    }

    @Test
    void fallingBlockEntitySpawnIsUndoOnly() {
        EntityPayload fallingBlock = entity(
                "minecraft:falling_block",
                "00000000-0000-0000-0000-000000000058",
                1.0D
        );

        assertFalse(this.policy.capture(WorldMutationSource.FALLING_BLOCK, null, fallingBlock).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.FALLING_BLOCK, null, fallingBlock).isPresent());
    }

    @Test
    void blockUpdateInspectionSkipsTransientEntitiesForDurableHistory() {
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:minecart"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:chest_minecart"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:arrow"));
        assertFalse(this.policy.shouldInspectMutation(WorldMutationSource.BLOCK_UPDATE, "minecraft:firework_rocket"));
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
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.PLAYER, null, item).isPresent());
    }

    @Test
    void primedTntSpawnIsUndoOnlyForExplosiveSources() {
        EntityPayload primedTnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000048", 1.0D);

        assertFalse(this.policy.capture(WorldMutationSource.EXPLOSIVE, null, primedTnt).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.EXPLOSIVE, null, primedTnt).isPresent());
    }

    @Test
    void primedTntSpawnIsUndoOnlyForRedstoneBlockUpdates() {
        EntityPayload primedTnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000049", 1.0D);

        assertFalse(this.policy.capture(WorldMutationSource.BLOCK_UPDATE, null, primedTnt).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.BLOCK_UPDATE, null, primedTnt).isPresent());
    }

    @Test
    void playerPrimedTntSpawnIsUndoOnly() {
        EntityPayload primedTnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000050", 1.0D);

        assertFalse(this.policy.capture(WorldMutationSource.PLAYER, null, primedTnt).isPresent());
        assertTrue(this.policy.captureUndoOnly(WorldMutationSource.PLAYER, null, primedTnt).isPresent());
    }

    @Test
    void primedTntUndoRedoAdjustmentStaysOutOfDurableDraft() {
        EntityPayload primedTnt = entity("minecraft:tnt", "00000000-0000-0000-0000-000000000051", 1.0D);
        StoredEntityChange undoAdjustment = new StoredEntityChange(
                primedTnt.entityId(),
                primedTnt.entityType(),
                primedTnt,
                null
        );

        assertFalse(this.policy.shouldPersistUndoRedoAdjustment(undoAdjustment));
    }

    @Test
    void placedEntityUndoRedoAdjustmentStaysInDurableDraft() {
        EntityPayload display = entity("minecraft:block_display", "00000000-0000-0000-0000-000000000052", 1.0D);
        StoredEntityChange undoAdjustment = new StoredEntityChange(
                display.entityId(),
                display.entityType(),
                display,
                null
        );

        assertTrue(this.policy.shouldPersistUndoRedoAdjustment(undoAdjustment));
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

    private static EntityPayload entityWithFlag(String type, String uuid, double x, String key, boolean value) {
        CompoundTag tag = entity(type, uuid, x).copyTag();
        tag.putBoolean(key, value);
        return new EntityPayload(tag);
    }
}
