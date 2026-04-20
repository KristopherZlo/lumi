package io.github.luma.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectLayoutTest {

    @Test
    void previewPathUsesPngPerVersion() {
        ProjectLayout layout = ProjectLayout.of(Path.of("projects"), "My:Project");

        assertEquals(Path.of("projects", "My_Project.mbp", "previews", "v0002.png"), layout.previewFile("v0002"));
        assertEquals(Path.of("projects", "My_Project.mbp", "recovery", "draft.bin.lz4"), layout.recoveryDraftFile());
    }
}
