package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickRollbackDraftPlanTest {

    private static final Instant NOW = Instant.parse("2026-05-08T00:00:00Z");

    @Test
    void planAppliesSavedValuesAndRecordsPreRollbackStateForUndo() {
        String entityId = "00000000-0000-0000-0000-000000000042";
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(change(1, "minecraft:stone", "minecraft:glass")),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 1.0D),
                        entity(entityId, 2.0D)
                ))
        );

        QuickRollbackDraftPlan plan = QuickRollbackDraftPlan.fromDraft("v0002", draft);

        assertFalse(plan.isEmpty());
        assertEquals("Lumi quick rollback", plan.actor());
        assertEquals(2, plan.totalChangeCount());
        assertTrue(plan.actionId().startsWith("quick-rollback-v0002-"));
        assertEquals("minecraft:glass", plan.blockChanges().getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", plan.blockChanges().getFirst().newValue().blockId());
        assertEquals(2.0D, x(plan.entityChanges().getFirst().oldValue()));
        assertEquals(1.0D, x(plan.entityChanges().getFirst().newValue()));
    }

    @Test
    void emptyDraftProducesNoRollbackWork() {
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of()
        );

        QuickRollbackDraftPlan plan = QuickRollbackDraftPlan.fromDraft("v0002", draft);

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.totalChangeCount());
    }

    @Test
    void selectedPlanRollsBackOnlyBoundsAndKeepsRemainingDraft() {
        String insideEntityId = "00000000-0000-0000-0000-000000000043";
        String outsideEntityId = "00000000-0000-0000-0000-000000000044";
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(
                        change(1, "minecraft:stone", "minecraft:glass"),
                        change(8, "minecraft:dirt", "minecraft:gold_block")
                ),
                List.of(
                        new StoredEntityChange(
                                insideEntityId,
                                "minecraft:block_display",
                                entity(insideEntityId, 1.0D),
                                entity(insideEntityId, 2.0D)
                        ),
                        new StoredEntityChange(
                                outsideEntityId,
                                "minecraft:block_display",
                                entity(outsideEntityId, 8.0D),
                                entity(outsideEntityId, 9.0D)
                        )
                )
        );

        QuickRollbackDraftPlan plan = QuickRollbackDraftPlan.fromDraft(
                "v0002",
                draft,
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 80, 5))
        );

        assertEquals(2, plan.totalChangeCount());
        assertEquals(new BlockPoint(1, 64, 1), plan.blockChanges().getFirst().pos());
        assertEquals(insideEntityId, plan.entityChanges().getFirst().entityId());
        assertNotNull(plan.remainingDraft());
        assertEquals(2, plan.remainingDraft().totalChangeCount());
        assertEquals(new BlockPoint(8, 64, 1), plan.remainingDraft().changes().getFirst().pos());
        assertEquals(outsideEntityId, plan.remainingDraft().entityChanges().getFirst().entityId());
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("UUID", entityId);
        tag.putString("id", "minecraft:block_display");
        tag.putDouble("x", x);
        tag.putDouble("y", 64.0D);
        tag.putDouble("z", 1.0D);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }

    private static double x(EntityPayload payload) {
        return payload.copyTag().getDouble("x").orElse(0.0D);
    }
}
