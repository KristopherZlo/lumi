package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.service.LiveActionJournal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectLiveActionContextTest {
    @Test
    void nestedPlayerCallKeepsOneRootAction() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID player = new UUID(0, 11);

        try (var outer = DirectLiveActionContext.open(journal, player)) {
            UUID root = DirectLiveActionContext.current(journal).orElseThrow();
            try (var inner = DirectLiveActionContext.open(journal, player)) {
                assertEquals(root, DirectLiveActionContext.current(journal).orElseThrow());
                journal.record(root, new BlockPosition(1, 2, 3), block("stone"), block("air"));
            }
            assertEquals(root, DirectLiveActionContext.current(journal).orElseThrow());
        }

        assertEquals(Optional.empty(), DirectLiveActionContext.current(journal));
        assertTrue(journal.prepareUndo(player).isPresent());
    }

    @Test
    void orphanedRootCannotBlockLaterPlayerActions() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID player = new UUID(0, 12);
        UUID orphaned = journal.begin(player);
        journal.record(
                orphaned, new BlockPosition(1, 2, 3), block("stone"), block("air"));

        try (var ignored = DirectLiveActionContext.open(journal, player)) {
            UUID current = DirectLiveActionContext.current(journal).orElseThrow();
            assertNotEquals(orphaned, current);
            journal.record(
                    current, new BlockPosition(4, 5, 6), block("air"), block("stone"));
        }

        var latest = journal.prepareUndo(player).orElseThrow();
        assertEquals(new BlockPosition(4, 5, 6), latest.expected().keySet().iterator().next());
        journal.complete(latest);
        assertEquals(orphaned, journal.prepareUndo(player).orElseThrow().actionId());
    }

    @Test
    void delayedRootsStopCreatingChildrenAtTheConfiguredDepth() {
        LiveActionJournal journal = new LiveActionJournal();
        UUID action = journal.begin(new UUID(0, 13));

        try (var ignored = DirectLiveActionContext.resume(journal, action, 32)) {
            var root = DirectLiveActionContext.currentRoot(journal).orElseThrow();
            assertEquals(32, root.depth());
            assertEquals(Optional.empty(), root.child(32));
        }
    }

    private static BlockSnapshot block(String id) {
        return new BlockSnapshot("minecraft:" + id, Optional.empty());
    }
}
