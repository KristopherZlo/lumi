package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDirtyScopeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void coalescesMarksAndMergesRestartedScope() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        TrackedProject project = trackedProject(layout);
        ProjectDirtyScope stored = ProjectDirtyScope.empty(
                project.project().id().toString(),
                "main",
                "v0001"
        );
        stored.markBlockSection(new ChunkSectionPoint(1, 2, 3));
        ProjectDirtyScopeRepository repository = new ProjectDirtyScopeRepository();
        repository.save(layout, stored);

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                repository,
                Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor()
        )) {
            ProjectDirtyScopeManager manager = new ProjectDirtyScopeManager(coordinator, repository);
            ChunkSectionPoint next = new ChunkSectionPoint(4, 5, 6);

            assertTrue(manager.markBlockSection(project, next));
            assertFalse(manager.markBlockSection(project, next));

            ProjectDirtyScope durable = manager.loadDurable(project);
            assertEquals(2, durable.blockSections().size());
            assertTrue(durable.blockSections().contains(next));
        }
    }

    @Test
    void runtimeSnapshotReturnsPendingScopeWithoutWaitingForPersistence() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("runtime-snapshot.mbp"));
        TrackedProject project = trackedProject(layout);
        ChunkSectionPoint section = new ChunkSectionPoint(7, 8, 9);

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                Executors.newSingleThreadExecutor()
        )) {
            ProjectDirtyScopeManager manager = new ProjectDirtyScopeManager(coordinator);
            manager.markBlockSection(project, section);

            ProjectDirtyScope snapshot = manager.runtimeSnapshot(project);

            assertEquals(List.of(section), snapshot.blockSections().stream().toList());
        }
    }

    @Test
    void replacesCommittedScopeWithRebasedRemainder() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        TrackedProject project = trackedProject(layout);
        ProjectDirtyScope expected = ProjectDirtyScope.empty(
                project.project().id().toString(), "main", "v0001"
        );
        ChunkSectionPoint saved = new ChunkSectionPoint(1, 2, 3);
        ChunkSectionPoint remainderSection = new ChunkSectionPoint(4, 5, 6);
        expected.markBlockSections(List.of(saved, remainderSection));
        ProjectDirtyScope remainder = ProjectDirtyScope.empty(
                project.project().id().toString(), "main", "v0001"
        );
        remainder.markBlockSection(remainderSection);
        ProjectDirtyScopeRepository repository = new ProjectDirtyScopeRepository();
        repository.save(layout, expected);

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(), new BaselineChunkRepository(), repository,
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()
        )) {
            ProjectDirtyScopeManager manager = new ProjectDirtyScopeManager(coordinator, repository);

            manager.replaceAfterCommit(project, expected, remainder, "v0002");

            ProjectDirtyScope stored = repository.load(layout).orElseThrow();
            assertEquals("v0002", stored.baseVersionId());
            assertEquals(List.of(remainderSection), stored.blockSections().stream().toList());
        }
    }

    @Test
    void verifiedApplyCanCreateScopeFromEmptyLedger() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        TrackedProject project = trackedProject(layout);
        ProjectDirtyScope empty = ProjectDirtyScope.empty(
                project.project().id().toString(), "main", "v0001"
        );
        ProjectDirtyScope applied = empty.copy();
        ChunkSectionPoint section = new ChunkSectionPoint(1, 2, 3);
        applied.markBlockSection(section);
        ProjectDirtyScopeRepository repository = new ProjectDirtyScopeRepository();

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(), new BaselineChunkRepository(), repository,
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()
        )) {
            ProjectDirtyScopeManager manager = new ProjectDirtyScopeManager(coordinator, repository);

            manager.replaceAfterCommit(project, empty, applied, "v0001");

            assertEquals(List.of(section), repository.load(layout).orElseThrow().blockSections().stream().toList());
        }
    }

    @Test
    void restartRebasesPublishedDirtyScopeWithoutDroppingItsSections() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        TrackedProject oldHead = trackedProject(layout);
        ProjectDirtyScope stored = ProjectDirtyScope.empty(
                oldHead.project().id().toString(), "main", "v0001"
        );
        ChunkSectionPoint section = new ChunkSectionPoint(7, 8, 9);
        stored.markBlockSection(section);
        ProjectDirtyScopeRepository repository = new ProjectDirtyScopeRepository();
        repository.save(layout, stored);
        TrackedProject publishedHead = new TrackedProject(
                layout,
                oldHead.project(),
                List.of(ProjectVariant.main("v0002", Instant.parse("2026-07-14T10:01:00Z")))
        );

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(), new BaselineChunkRepository(), repository,
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()
        )) {
            ProjectDirtyScope durable = new ProjectDirtyScopeManager(coordinator, repository)
                    .loadDurable(publishedHead);

            assertEquals("v0002", durable.baseVersionId());
            assertEquals(List.of(section), durable.blockSections().stream().toList());
            assertEquals("v0002", repository.load(layout).orElseThrow().baseVersionId());
        }
    }

    private static TrackedProject trackedProject(ProjectLayout layout) {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        BuildProject project = BuildProject.createWorldWorkspace("World", "minecraft:overworld", now);
        return new TrackedProject(layout, project, List.of(ProjectVariant.main("v0001", now)));
    }
}
