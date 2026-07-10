package io.github.luma.ui.overlay;

import io.github.luma.domain.model.RecoveryDraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChangesOverlayCoordinatorTest {

    @Test
    void hotDraftsWaitBeforeBuildingOverlayMeshes() {
        Instant now = Instant.parse("2026-07-04T12:00:00Z");

        assertTrue(PendingChangesOverlayCoordinator.shouldDeferHotDraft(draft(now.minusMillis(250)), now));
        assertFalse(PendingChangesOverlayCoordinator.shouldDeferHotDraft(draft(now.minusSeconds(2)), now));
    }

    @Test
    void stalePreparedMeshesAreDiscarded() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/ui/overlay/PendingChangesOverlayCoordinator.java"
        ));

        assertTrue(source.contains("PendingChangesOverlayRenderer.discard(prepared);"));
    }

    private static RecoveryDraft draft(Instant updatedAt) {
        return new RecoveryDraft(
                "project",
                "main",
                "v1",
                "Alex",
                null,
                updatedAt.minusSeconds(10),
                updatedAt,
                List.of()
        );
    }
}
