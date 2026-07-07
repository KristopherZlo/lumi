package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestoreUndoActionFactoryTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    private final RestoreUndoActionFactory factory = new RestoreUndoActionFactory();

    @Test
    void quickRollbackUndoActionReappliesPreRestoreDraftState() {
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(change(1, "minecraft:stone", "minecraft:glass"))
        );

        RestoreUndoAction action = this.factory.quickRollbackUndoAction(
                "project",
                "minecraft:overworld",
                "v0002",
                draft
        );

        assertNotNull(action);
        assertEquals("Lumi quick rollback", action.actor());
        assertEquals("project", action.projectId());
        assertEquals("minecraft:overworld", action.dimensionId());
        assertEquals("minecraft:glass", action.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", action.changes().getFirst().newValue().blockId());
    }

    @Test
    void restoreUndoActionUsesAppliedResetTransitions() {
        RestoreUndoAction action = this.factory.restoreUndoAction(
                "project",
                "minecraft:overworld",
                "v0001",
                List.of(change(1, "minecraft:glass", "minecraft:stone")),
                List.of()
        );

        assertNotNull(action);
        assertEquals("Lumi quick rollback", action.actor());
        assertEquals("restore-v0001-", action.actionId().substring(0, "restore-v0001-".length()));
        assertEquals("minecraft:glass", action.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", action.changes().getFirst().newValue().blockId());
    }

    private static StoredBlockChange change(int offset, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(offset, 64, 0),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
