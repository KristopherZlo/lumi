package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDirtyScopeRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsCoalescedBlockSectionsAndEntityChunks() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        ProjectDirtyScope scope = new ProjectDirtyScope(
                "project",
                "main",
                "v0002",
                List.of(new ChunkSectionPoint(2, -3, 4), new ChunkSectionPoint(2, -3, 4)),
                List.of(new ChunkPoint(7, 8), new ChunkPoint(7, 8))
        );
        ProjectDirtyScopeRepository repository = new ProjectDirtyScopeRepository();

        repository.save(layout, scope);
        ProjectDirtyScope restored = repository.load(layout).orElseThrow();

        assertEquals("v0002", restored.baseVersionId());
        assertEquals(scope.blockSections(), restored.blockSections());
        assertEquals(scope.entityChunks(), restored.entityChunks());
        assertEquals(1, restored.blockSections().size());
        assertEquals(1, restored.entityChunks().size());

        repository.delete(layout);

        assertFalse(repository.load(layout).isPresent());
    }

    @Test
    void repeatedMarksAreNoOps() {
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v0001");
        ChunkSectionPoint section = new ChunkSectionPoint(1, 2, 3);

        assertTrue(scope.markBlockSection(section));
        assertFalse(scope.markBlockSection(section));
    }

    @Test
    void splitsSpatialScopeAndRebasesRemainder() {
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v1");
        ChunkSectionPoint selected = new ChunkSectionPoint(1, 2, 3);
        ChunkSectionPoint remainder = new ChunkSectionPoint(4, 5, 6);
        scope.markBlockSections(List.of(selected, remainder));
        scope.markEntityChunk(new ChunkPoint(1, 2));

        ProjectDirtyScope.Split split = scope.split(selected::equals, chunk -> false);

        assertEquals(Set.of(selected), split.selected().blockSections());
        assertEquals(Set.of(remainder), split.remainder().blockSections());
        assertEquals(Set.of(new ChunkPoint(1, 2)), split.remainder().entityChunks());
        assertEquals("v2", split.remainder().withBaseVersionId("v2").baseVersionId());
    }
}
