package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentChangesOverlayRendererStateTest {

    @AfterEach
    void tearDown() {
        RecentChangesOverlayRenderer.clear();
    }

    @Test
    void showAndClearToggleVisibility() {
        UndoRedoAction action = action();
        action.recordChange(new StoredBlockChange(
                new BlockPoint(10, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        ), Instant.parse("2026-04-23T08:00:01Z"));

        RecentChangesOverlayRenderer.show("project", List.of(action));

        assertTrue(RecentChangesOverlayRenderer.visible());
        assertTrue(RecentChangesOverlayRenderer.visibleSurfaceEntryCountForTest(10.5D, 64.5D, 10.5D) > 0);
        assertEquals(1, RecentChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(1, RecentChangesOverlayRenderer.meshPrimitiveCountForTest());
        assertEquals(1, RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(10.5D, 64.5D, 10.5D, 1));
        assertEquals(0, RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(1024.0D, 64.5D, 1024.0D, 1));

        RecentChangesOverlayRenderer.clear();

        assertFalse(RecentChangesOverlayRenderer.visible());
    }

    @Test
    void topUndoAndRedoPreviewActionsUseDirectionalColors() {
        UndoRedoAction first = action("first", 10);
        UndoRedoAction second = action("second", 11);

        List<Integer> undoColors = RecentChangesOverlayRenderer.outlineColorsForTest(
                List.of(first, second),
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );
        List<Integer> redoColors = RecentChangesOverlayRenderer.outlineColorsForTest(
                List.of(first, second),
                RecentChangesOverlayCoordinator.PreviewTarget.REDO
        );

        assertEquals(RecentChangesOverlayRenderer.UNDO_TARGET_OUTLINE, undoColors.getFirst());
        assertEquals(RecentChangesOverlayRenderer.RECENT_ACTION_OUTLINE, undoColors.get(1));
        assertEquals(RecentChangesOverlayRenderer.REDO_TARGET_OUTLINE, redoColors.getFirst());
        assertEquals(RecentChangesOverlayRenderer.RECENT_ACTION_OUTLINE, redoColors.get(1));
    }

    @Test
    void heldActionButtonPreviewShowsUndoAndRedoTargetsTogether() {
        UndoRedoAction undoFirst = action("undo-first", 10);
        UndoRedoAction undoSecond = action("undo-second", 11);
        UndoRedoAction redoFirst = action("redo-first", 20);
        UndoRedoAction redoSecond = action("redo-second", 21);

        List<Integer> colors = RecentChangesOverlayRenderer.outlineColorsForTest(
                List.of(undoFirst, undoSecond),
                List.of(redoFirst, redoSecond),
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH
        );
        List<BlockPoint> positions = RecentChangesOverlayRenderer.previewPositionsForTest(
                List.of(undoFirst, undoSecond),
                List.of(redoFirst, redoSecond),
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH
        );

        assertEquals(new BlockPoint(10, 64, 10), positions.get(0));
        assertEquals(RecentChangesOverlayRenderer.UNDO_TARGET_OUTLINE, colors.get(0));
        assertEquals(new BlockPoint(11, 64, 10), positions.get(1));
        assertEquals(RecentChangesOverlayRenderer.RECENT_ACTION_OUTLINE, colors.get(1));
        assertEquals(new BlockPoint(20, 64, 10), positions.get(2));
        assertEquals(RecentChangesOverlayRenderer.REDO_TARGET_OUTLINE, colors.get(2));
        assertEquals(new BlockPoint(21, 64, 10), positions.get(3));
        assertEquals(RecentChangesOverlayRenderer.RECENT_ACTION_OUTLINE, colors.get(3));
    }

    @Test
    void previewEntriesFollowUndoOrRedoApplyDirection() {
        UndoRedoAction action = action();
        action.recordChange(new StoredBlockChange(
                new BlockPoint(10, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        ), Instant.parse("2026-04-23T08:00:01Z"));
        action.recordChange(new StoredBlockChange(
                new BlockPoint(11, 64, 10),
                new StatePayload(state("minecraft:dirt"), null),
                new StatePayload(state("minecraft:gold_block"), null)
        ), Instant.parse("2026-04-23T08:00:02Z"));

        List<BlockPoint> undoPositions = RecentChangesOverlayRenderer.previewPositionsForTest(
                List.of(action),
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );
        List<BlockPoint> redoPositions = RecentChangesOverlayRenderer.previewPositionsForTest(
                List.of(action),
                RecentChangesOverlayCoordinator.PreviewTarget.REDO
        );

        assertEquals(new BlockPoint(11, 64, 10), undoPositions.getFirst());
        assertEquals(new BlockPoint(10, 64, 10), redoPositions.getFirst());
    }

    @Test
    void largeRecentActionUsesExposedSurfaceMeshes() {
        UndoRedoAction action = action();
        for (StoredBlockChange change : denseCubeChanges()) {
            action.recordChange(change, Instant.parse("2026-04-23T08:00:01Z"));
        }

        RecentChangesOverlayRenderer.show("project", List.of(action));

        assertTrue(RecentChangesOverlayRenderer.visible());
        int renderedSurfaceEntries = RecentChangesOverlayRenderer.visibleSurfaceEntryCountForTest(10.5D, 70.5D, 10.5D);
        assertEquals(2168, renderedSurfaceEntries);
        assertEquals(0, RecentChangesOverlayRenderer.visibleAggregateBoxCountForTest(10.5D, 70.5D, 10.5D));
        assertEquals(8, RecentChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(2168, RecentChangesOverlayRenderer.meshPrimitiveCountForTest());
    }

    @Test
    void giantRecentSurfaceMeshIsChunkedAndRenderDistanceCulled() {
        int size = 32;
        UndoRedoAction action = action();
        recordCuboidChanges(action, size, size, size, 64);

        RecentChangesOverlayRenderer.show("project", List.of(action));

        int expectedSurfaceEntries = exposedShellBlockCount(size, size, size);
        assertTrue(RecentChangesOverlayRenderer.visible());
        assertEquals(expectedSurfaceEntries, RecentChangesOverlayRenderer.visibleSurfaceEntryCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(0, RecentChangesOverlayRenderer.visibleAggregateBoxCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(8, RecentChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(expectedSurfaceEntries, RecentChangesOverlayRenderer.meshPrimitiveCountForTest());
        assertEquals(8, RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(8.5D, 80.5D, 8.5D, 0));
        assertEquals(0, RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(2048.0D, 80.5D, 2048.0D, 0));
    }

    @Test
    void giantRecentOverlayUsesSurfaceMeshInsteadOfMergedVolumeChunks() {
        UndoRedoAction action = action();
        int sizeX = 64;
        int sizeY = 40;
        int sizeZ = 40;
        recordCuboidChanges(action, sizeX, sizeY, sizeZ, 64);

        RecentChangesOverlayRenderer.show("project", List.of(action));

        int expectedSurfaceEntries = exposedShellBlockCount(sizeX, sizeY, sizeZ);
        assertTrue(RecentChangesOverlayRenderer.visible());
        assertEquals(expectedSurfaceEntries, RecentChangesOverlayRenderer.visibleSurfaceEntryCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(0, RecentChangesOverlayRenderer.visibleAggregateBoxCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(34, RecentChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(expectedSurfaceEntries, RecentChangesOverlayRenderer.meshPrimitiveCountForTest());
        int nearbySections = RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(8.5D, 80.5D, 8.5D, 0);
        assertTrue(nearbySections > 0);
        assertTrue(nearbySections < RecentChangesOverlayRenderer.meshSectionCountForTest());
        assertEquals(0, RecentChangesOverlayRenderer.visibleMeshSectionCountForTest(2048.0D, 80.5D, 2048.0D, 0));
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static UndoRedoAction action() {
        return new UndoRedoAction(
                "action",
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:00Z")
        );
    }

    private static UndoRedoAction action(String id, int x) {
        UndoRedoAction action = new UndoRedoAction(
                id,
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:00Z")
        );
        action.recordChange(new StoredBlockChange(
                new BlockPoint(x, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        ), Instant.parse("2026-04-23T08:00:01Z"));
        return action;
    }

    private static List<StoredBlockChange> denseCubeChanges() {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = 0; z < 20; z++) {
                    changes.add(new StoredBlockChange(
                            new BlockPoint(x, y, z),
                            new StatePayload(state("minecraft:stone"), null),
                            new StatePayload(state("minecraft:glass"), null)
                    ));
                }
            }
        }
        return List.copyOf(changes);
    }

    private static void recordCuboidChanges(UndoRedoAction action, int sizeX, int sizeY, int sizeZ, int minY) {
        StatePayload oldValue = new StatePayload(state("minecraft:stone"), null);
        StatePayload newValue = new StatePayload(state("minecraft:glass"), null);
        Instant changedAt = Instant.parse("2026-04-23T08:00:01Z");
        for (int x = 0; x < sizeX; x++) {
            for (int y = minY; y < minY + sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    action.recordChange(new StoredBlockChange(new BlockPoint(x, y, z), oldValue, newValue), changedAt);
                }
            }
        }
    }

    private static int exposedShellBlockCount(int sizeX, int sizeY, int sizeZ) {
        return (sizeX * sizeY * sizeZ) - ((sizeX - 2) * (sizeY - 2) * (sizeZ - 2));
    }
}
