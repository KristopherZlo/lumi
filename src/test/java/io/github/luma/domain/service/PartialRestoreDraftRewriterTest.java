package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void preservesPendingChangesOutsideComplexZoneCells(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir);
        RecoveryRepository repository = new RecoveryRepository();
        RecoveryDraft pending = draft("Alex", WorldMutationSource.PLAYER, List.of(
                change(1, "minecraft:stone", "minecraft:glass"),
                change(20, "minecraft:dirt", "minecraft:gold_block")
        ));
        Predicate<BlockPoint> firstCellOnly = point -> point.x() < 16;

        this.rewriter.preserveOutsideRestoredRegion(
                layout,
                pending,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(31, 64, 0)),
                PartialRestoreMode.SELECTED_AREA,
                firstCellOnly
        );

        RecoveryDraft saved = repository.loadDraft(layout).orElseThrow();
        assertEquals(List.of(new BlockPoint(20, 64, 0)), saved.changes().stream().map(StoredBlockChange::pos).toList());
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
