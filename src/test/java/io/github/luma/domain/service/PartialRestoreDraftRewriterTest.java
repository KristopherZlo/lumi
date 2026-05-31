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
import static org.junit.jupiter.api.Assertions.assertNull;

class PartialRestoreDraftRewriterTest {

    private static final Instant NOW = Instant.parse("2026-05-31T00:00:00Z");

    private final PartialRestoreDraftRewriter rewriter = new PartialRestoreDraftRewriter();

    @Test
    void restoredChangesOverwritePendingDraftWithoutCreatingSavedVersionState() {
        RecoveryDraft pending = draft("Alex", WorldMutationSource.PLAYER, List.of(
                change(1, "minecraft:stone", "minecraft:glass"),
                change(20, "minecraft:dirt", "minecraft:gold_block")
        ));
        RecoveryDraft restored = draft("Lumi", WorldMutationSource.RESTORE, List.of(
                change(1, "minecraft:glass", "minecraft:stone"),
                change(2, "minecraft:oak_planks", "minecraft:diamond_block")
        ));

        RecoveryDraft merged = this.rewriter.mergeRestoredChanges(pending, restored, NOW);

        assertEquals("Lumi", merged.actor());
        assertEquals(WorldMutationSource.RESTORE, merged.mutationSource());
        assertEquals(
                List.of(new BlockPoint(20, 64, 0), new BlockPoint(2, 64, 0)),
                merged.changes().stream().map(StoredBlockChange::pos).toList()
        );
        assertEquals("minecraft:gold_block", merged.changes().getFirst().newValue().blockId());
        assertEquals("minecraft:diamond_block", merged.changes().get(1).newValue().blockId());
    }

    @Test
    void restoredChangesCanClearPendingDraftWhenTargetMatchesBase() {
        RecoveryDraft pending = draft("Alex", WorldMutationSource.PLAYER, List.of(
                change(1, "minecraft:stone", "minecraft:glass")
        ));
        RecoveryDraft restored = draft("Lumi", WorldMutationSource.RESTORE, List.of(
                change(1, "minecraft:glass", "minecraft:stone")
        ));

        assertNull(this.rewriter.mergeRestoredChanges(pending, restored, NOW));
    }

    private static RecoveryDraft draft(
            String actor,
            WorldMutationSource source,
            List<StoredBlockChange> changes
    ) {
        return new RecoveryDraft(
                "project",
                "main",
                "v0001",
                actor,
                source,
                NOW,
                NOW,
                changes
        );
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload(oldBlock),
                payload(newBlock)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
