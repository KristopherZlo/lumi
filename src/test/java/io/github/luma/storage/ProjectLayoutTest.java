package io.github.luma.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectLayoutTest {

    @Test
    void previewPathUsesPngPerVersion() {
        ProjectLayout layout = ProjectLayout.of(Path.of("projects"), "My:Project");

        assertEquals(Path.of("projects", "My_Project.mbp", "previews", "v0002.png"), layout.previewFile("v0002"));
        assertEquals(Path.of("projects", "My_Project.mbp", "preview-requests", "v0002.json"), layout.previewRequestFile("v0002"));
        assertEquals(Path.of("projects", "My_Project.mbp", "history-tombstones.json"), layout.historyTombstonesFile());
        assertEquals(Path.of("projects", "My_Project.mbp", "recovery", "draft.bin.lz4"), layout.recoveryDraftFile());
        assertEquals(Path.of("projects", "My_Project.mbp", "recovery", "operation-draft.bin.lz4"), layout.recoveryOperationDraftFile());
    }

    @Test
    void safeFolderNameRemovesPathLikeCharacters() {
        ProjectLayout layout = ProjectLayout.of(Path.of("projects"), "../Unsafe Project");

        assertEquals(Path.of("projects", "Unsafe_Project.mbp"), layout.root());
    }

    @Test
    void storageFilesRejectPathTraversalIds() {
        ProjectLayout layout = ProjectLayout.of(Path.of("projects"), "My Project");

        assertThrows(IllegalArgumentException.class, () -> layout.versionFile("../escape"));
        assertThrows(IllegalArgumentException.class, () -> layout.patchDataFile("patch/escape"));
        assertThrows(IllegalArgumentException.class, () -> layout.snapshotFile("snapshot\\escape"));
        assertThrows(IllegalArgumentException.class, () -> layout.previewRequestFile(""));
    }
}
