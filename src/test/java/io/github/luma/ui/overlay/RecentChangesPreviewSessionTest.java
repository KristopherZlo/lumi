package io.github.luma.ui.overlay;

import io.github.luma.domain.model.UndoRedoAction;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals("large", afterLiveEdits.actions().get(0).id());
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
        assertEquals("tiny", afterRelease.actions().get(0).id());
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
                () -> snapshot(90L, action("redo"))
        );

        assertEquals(90L, redo.key().revision());
        assertEquals(RecentChangesOverlayCoordinator.PreviewTarget.REDO, redo.key().previewTarget());
        assertEquals("redo", redo.actions().get(0).id());
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

    private static RecentChangesPreviewSession.ActionSnapshot snapshot(long revision, UndoRedoAction action) {
        return new RecentChangesPreviewSession.ActionSnapshot(revision, List.of(action));
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
}
