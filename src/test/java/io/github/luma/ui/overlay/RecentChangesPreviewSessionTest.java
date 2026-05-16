package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.UndoRedoAction;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentChangesPreviewSessionTest {

    @Test
    void pinsInitialUndoSnapshotWhileOverlayHoldContinues() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();
        AtomicInteger snapshotReads = new AtomicInteger();

        RecentChangesPreviewSession.PinnedPreview first = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(snapshotReads, 50L, action("large"))
        );
        RecentChangesPreviewSession.PinnedPreview afterLiveEdits = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(snapshotReads, 90L, action("tiny"))
        );

        assertSame(first, afterLiveEdits);
        assertEquals(1, snapshotReads.get());
        assertEquals(50L, afterLiveEdits.key().revision());
        assertEquals("large", afterLiveEdits.undoActions().get(0).id());
    }

    @Test
    void refreshesPinnedPreviewWhenStreamRevisionChanges() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();
        AtomicInteger snapshotReads = new AtomicInteger();

        RecentChangesPreviewSession.PinnedPreview first = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH,
                50L,
                () -> new RecentChangesPreviewSession.ActionSnapshot(
                        50L,
                        List.of(blockAction("undo-before")),
                        List.of(blockAction("redo-before"))
                )
        );
        RecentChangesPreviewSession.PinnedPreview afterUndoRedo = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH,
                90L,
                () -> {
                    snapshotReads.incrementAndGet();
                    return new RecentChangesPreviewSession.ActionSnapshot(
                            90L,
                            List.of(blockAction("undo-after")),
                            List.of(blockAction("redo-after"))
                    );
                }
        );

        assertEquals(1, snapshotReads.get());
        assertEquals(50L, first.key().revision());
        assertEquals(90L, afterUndoRedo.key().revision());
        assertEquals("undo-after", afterUndoRedo.undoActions().get(0).id());
        assertEquals("redo-after", afterUndoRedo.redoActions().get(0).id());
    }

    @Test
    void startsNewPinnedRevisionAfterRelease() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(50L, action("large"))
        );
        session.clear();
        RecentChangesPreviewSession.PinnedPreview afterRelease = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(90L, action("tiny"))
        );

        assertEquals(90L, afterRelease.key().revision());
        assertEquals("tiny", afterRelease.undoActions().get(0).id());
    }

    @Test
    void switchesPinWhenPreviewTargetChanges() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(50L, action("undo"))
        );
        RecentChangesPreviewSession.PinnedPreview redo = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.REDO,
                () -> redoSnapshot(90L, action("redo"))
        );

        assertEquals(90L, redo.key().revision());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.REDO, redo.key().previewTarget());
        assertEquals("redo", redo.redoActions().get(0).id());
    }

    @Test
    void switchesPinWhenProjectChanges() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        session.request(
                "project-a",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(50L, action("project-a"))
        );
        RecentChangesPreviewSession.PinnedPreview nextProject = session.request(
                "project-b",
                RecentChangesOverlayCoordinator.PreviewTarget.UNDO,
                () -> snapshot(90L, action("project-b"))
        );

        assertEquals("project-b", nextProject.key().projectId());
        assertEquals(90L, nextProject.key().revision());
    }

    @Test
    void pinsUndoAndRedoSnapshotsTogetherForHeldActionButtonPreview() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        RecentChangesPreviewSession.PinnedPreview preview = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH,
                () -> new RecentChangesPreviewSession.ActionSnapshot(
                        120L,
                        List.of(blockAction("undo")),
                        List.of(blockAction("redo"))
                )
        );

        assertEquals(120L, preview.key().revision());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.BOTH, preview.key().previewTarget());
        assertEquals("undo", preview.undoActions().get(0).id());
        assertEquals("redo", preview.redoActions().get(0).id());
        assertTrue(preview.hasBlockPreview());
    }

    @Test
    void emptyHeldActionButtonPreviewDoesNotClaimOverlay() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        RecentChangesPreviewSession.PinnedPreview preview = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH,
                () -> new RecentChangesPreviewSession.ActionSnapshot(
                        120L,
                        List.of(action("undo")),
                        List.of(action("redo"))
                )
        );

        assertFalse(preview.hasBlockPreview());
    }

    @Test
    void hiddenHeldActionButtonPreviewDoesNotClaimOverlay() {
        RecentChangesPreviewSession session = new RecentChangesPreviewSession();

        RecentChangesPreviewSession.PinnedPreview preview = session.request(
                "project",
                RecentChangesOverlayCoordinator.PreviewTarget.BOTH,
                () -> new RecentChangesPreviewSession.ActionSnapshot(
                        120L,
                        List.of(hiddenBlockAction("undo")),
                        List.of(hiddenBlockAction("redo"))
                )
        );

        assertFalse(preview.hasBlockPreview());
    }

    private static RecentChangesPreviewSession.ActionSnapshot snapshot(long revision, UndoRedoAction action) {
        return new RecentChangesPreviewSession.ActionSnapshot(revision, List.of(action), List.of());
    }

    private static RecentChangesPreviewSession.ActionSnapshot redoSnapshot(long revision, UndoRedoAction action) {
        return new RecentChangesPreviewSession.ActionSnapshot(revision, List.of(), List.of(action));
    }

    private static RecentChangesPreviewSession.ActionSnapshot snapshot(
            AtomicInteger snapshotReads,
            long revision,
            UndoRedoAction action
    ) {
        snapshotReads.incrementAndGet();
        return snapshot(revision, action);
    }

    private static UndoRedoAction action(String id) {
        return new UndoRedoAction(
                id,
                "Alex",
                "project",
                "minecraft:overworld",
                Instant.parse("2026-04-23T08:00:00Z"),
                Instant.parse("2026-04-23T08:00:00Z")
        );
    }

    private static UndoRedoAction blockAction(String id) {
        UndoRedoAction action = action(id);
        action.recordChange(new StoredBlockChange(
                new BlockPoint(10, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null)
        ), Instant.parse("2026-04-23T08:00:01Z"));
        return action;
    }

    private static UndoRedoAction hiddenBlockAction(String id) {
        UndoRedoAction action = action(id);
        action.recordChange(new StoredBlockChange(
                new BlockPoint(10, 64, 10),
                new StatePayload(state("minecraft:stone"), null),
                new StatePayload(state("minecraft:glass"), null),
                true
        ), Instant.parse("2026-04-23T08:00:01Z"));
        return action;
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
