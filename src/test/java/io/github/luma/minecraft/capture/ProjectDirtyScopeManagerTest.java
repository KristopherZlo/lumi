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

    private static TrackedProject trackedProject(ProjectLayout layout) {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        BuildProject project = BuildProject.createWorldWorkspace("World", "minecraft:overworld", now);
        return new TrackedProject(layout, project, List.of(ProjectVariant.main("v0001", now)));
    }
}
