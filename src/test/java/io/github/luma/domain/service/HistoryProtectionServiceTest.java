package io.github.luma.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.luma.domain.model.HistoryProtectionState;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryProtectionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsActiveSaveAndRestoreWithoutPersistingTransientState() throws Exception {
        HistoryProtectionService service = new HistoryProtectionService();
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));

        assertEquals(HistoryProtectionState.SAVING, service.load(layout, operation("save-version")).state());
        assertEquals(HistoryProtectionState.RESTORING, service.load(layout, operation("quick-rollback")).state());
        assertFalse(java.nio.file.Files.exists(layout.historyProtectionFile()));
    }

    @Test
    void durableDegradedStateOverridesActiveOperation() throws Exception {
        HistoryProtectionService service = new HistoryProtectionService();
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));

        service.markDegraded(layout, "Restore verification mismatch");

        var status = service.load(layout, operation("save-version"));
        assertEquals(HistoryProtectionState.DEGRADED, status.state());
        assertEquals("Restore verification mismatch", status.detail());
    }

    @Test
    void expectedValidationFailuresDoNotDegradeHistory() {
        HistoryProtectionService service = new HistoryProtectionService();

        assertFalse(service.shouldMarkDegraded(new IllegalArgumentException("No pending tracked changes")));
        assertTrue(service.shouldMarkDegraded(new java.io.IOException("Patch write failed")));
        assertTrue(service.shouldMarkDegraded(new IllegalStateException("Restore verification mismatch")));
    }

    @Test
    void exposesActionlessDirtyScopeWithoutPlayerDraft() throws Exception {
        HistoryProtectionService service = new HistoryProtectionService();
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        assertFalse(service.hasSafetyChanges(layout));

        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v1");
        scope.markBlockSection(new io.github.luma.domain.model.ChunkSectionPoint(1, 2, 3));
        new ProjectDirtyScopeRepository().save(layout, scope);

        assertTrue(service.hasSafetyChanges(layout));
    }

    @Test
    void exposesOnlySafetySectionsInsideSelectedWorkZone() throws Exception {
        HistoryProtectionService service = new HistoryProtectionService();
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("zone-project.mbp"));
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v1");
        scope.markBlockSection(new io.github.luma.domain.model.ChunkSectionPoint(4, 5, 6));
        new ProjectDirtyScopeRepository().save(layout, scope);

        WorkZone matching = new WorkZone(
                "zone", "project", "House", 0, java.util.List.of(new WorkZoneCell(4, 6, 5)),
                "player", Instant.EPOCH, Instant.EPOCH
        );
        WorkZone outside = new WorkZone(
                "outside", "project", "Outside", 0, java.util.List.of(new WorkZoneCell(8, 6, 8)),
                "player", Instant.EPOCH, Instant.EPOCH
        );

        assertTrue(service.hasSafetyChanges(layout, matching));
        assertFalse(service.hasSafetyChanges(layout, outside));
    }

    private static OperationSnapshot operation(String label) {
        Instant now = Instant.now();
        return new OperationSnapshot(
                new OperationHandle("operation", "project", label, now, false),
                OperationStage.PREPARING,
                OperationProgress.empty("items"),
                "Preparing",
                now
        );
    }
}
