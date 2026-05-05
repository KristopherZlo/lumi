package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChangesOverlayRendererStateTest {

    @AfterEach
    void tearDown() {
        PendingChangesOverlayRenderer.clear();
    }

    @Test
    void pendingOverlayUsesCumulativeDraftChanges() {
        List<StoredBlockChange> changes = new ArrayList<>();
        changes.addAll(lineChanges(100_000, 0));
        changes.addAll(lineChanges(64, 200_000));
        PendingChangesOverlaySnapshot snapshot = PendingChangesOverlaySnapshot.fromDraft("project", draft(changes));

        PendingChangesOverlayRenderer.activate(PendingChangesOverlayRenderer.prepare(snapshot, false));

        int aggregateBoxCount = PendingChangesOverlayRenderer.visibleAggregateBoxCountForTest(0.5D, 64.5D, 0.5D);
        assertTrue(PendingChangesOverlayRenderer.visible());
        assertEquals(100_064, snapshot.blockChanges().size());
        assertTrue(aggregateBoxCount > 1);
        assertTrue(aggregateBoxCount <= 128);
        assertEquals(aggregateBoxCount, PendingChangesOverlayRenderer.meshPrimitiveCountForTest());
    }

    @Test
    void justOverCapPendingOverlayFallsBackToMergedVolumeMesh() {
        int changedBlockCount = CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT + 1;
        PendingChangesOverlaySnapshot snapshot = PendingChangesOverlaySnapshot.fromDraft(
                "project",
                draft(lineChanges(changedBlockCount, 0))
        );

        PendingChangesOverlayRenderer.activate(PendingChangesOverlayRenderer.prepare(snapshot, false));

        int aggregateBoxCount = PendingChangesOverlayRenderer.visibleAggregateBoxCountForTest(8.5D, 80.5D, 8.5D);
        assertTrue(PendingChangesOverlayRenderer.visible());
        assertEquals(0, PendingChangesOverlayRenderer.visibleSurfaceEntryCountForTest(8.5D, 80.5D, 8.5D));
        assertTrue(aggregateBoxCount > 1);
        assertTrue(aggregateBoxCount <= 128);
        assertEquals(aggregateBoxCount, PendingChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(aggregateBoxCount, PendingChangesOverlayRenderer.meshPrimitiveCountForTest());
    }

    @Test
    void emptySnapshotClearsOverlay() {
        PendingChangesOverlayRenderer.activate(PendingChangesOverlayRenderer.prepare(
                PendingChangesOverlaySnapshot.empty("project"),
                false
        ));

        assertFalse(PendingChangesOverlayRenderer.visible());
    }

    private static RecoveryDraft draft(List<StoredBlockChange> changes) {
        return new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:01Z"),
                changes,
                List.of()
        );
    }

    private static List<StoredBlockChange> lineChanges(int count, int startX) {
        List<StoredBlockChange> changes = new ArrayList<>(count);
        StatePayload oldValue = new StatePayload(state("minecraft:stone"), null);
        StatePayload newValue = new StatePayload(state("minecraft:glass"), null);
        for (int index = 0; index < count; index++) {
            changes.add(new StoredBlockChange(new BlockPoint(startX + index, 64, 0), oldValue, newValue));
        }
        return List.copyOf(changes);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
