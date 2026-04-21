package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectCleanupPolicy;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCleanupRepositoryTest {

    @TempDir
    Path tempDir;

    private final ProjectCleanupRepository repository = new ProjectCleanupRepository();

    @Test
    void inspectFindsOnlyDisposableFiles() throws Exception {
        ProjectLayout layout = this.seedLayout();

        var candidates = this.repository.inspect(layout, new ProjectCleanupPolicy(
                Set.of("snapshot-0001.bin.lz4"),
                Set.of("v0001.png"),
                true
        ));

        assertEquals(4, candidates.size());
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.relativePath().equals("snapshots/snapshot-9999.bin.lz4")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.relativePath().equals("previews/stale.png")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.relativePath().equals("cache/render-cache.bin")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.relativePath().equals("recovery/operation-draft.bin.lz4")));
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.relativePath().contains("baseline-chunks")));
    }

    @Test
    void applyDeletesCandidatesButPreservesReferencedFilesAndBaselineChunks() throws Exception {
        ProjectLayout layout = this.seedLayout();

        var deleted = this.repository.apply(layout, new ProjectCleanupPolicy(
                Set.of("snapshot-0001.bin.lz4"),
                Set.of("v0001.png"),
                true
        ));

        assertEquals(4, deleted.size());
        assertTrue(Files.exists(layout.snapshotFile("snapshot-0001")));
        assertFalse(Files.exists(layout.snapshotFile("snapshot-9999")));
        assertTrue(Files.exists(layout.previewFile("v0001")));
        assertFalse(Files.exists(layout.previewsDir().resolve("stale.png")));
        assertFalse(Files.exists(layout.cacheDir().resolve("render-cache.bin")));
        assertTrue(Files.exists(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4")));
        assertFalse(Files.exists(layout.recoveryOperationDraftFile()));
    }

    @Test
    void inspectKeepsOperationDraftWhenPolicyDisablesIt() throws Exception {
        ProjectLayout layout = this.seedLayout();

        var candidates = this.repository.inspect(layout, new ProjectCleanupPolicy(
                Set.of("snapshot-0001.bin.lz4"),
                Set.of("v0001.png"),
                false
        ));

        assertEquals(3, candidates.size());
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.relativePath().equals("recovery/operation-draft.bin.lz4")));
    }

    private ProjectLayout seedLayout() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("tower.mbp"));
        ProjectRepository projectRepository = new ProjectRepository();
        projectRepository.initializeLayout(layout);

        Files.write(layout.snapshotFile("snapshot-0001"), new byte[]{1});
        Files.write(layout.snapshotFile("snapshot-9999"), new byte[]{2});
        Files.write(layout.previewFile("v0001"), new byte[]{3});
        Files.write(layout.previewsDir().resolve("stale.png"), new byte[]{4});
        Files.write(layout.cacheDir().resolve("render-cache.bin"), new byte[]{5});
        Files.createDirectories(layout.cacheDir().resolve("baseline-chunks"));
        Files.write(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4"), new byte[]{6});
        Files.write(layout.recoveryOperationDraftFile(), new byte[]{7});
        return layout;
    }
}
