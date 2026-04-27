package io.github.luma.domain.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArchiveServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void packageRootUsesGameRootLumiProjectsFolder() throws Exception {
        ProjectArchiveService service = new ProjectArchiveService(() -> this.tempDir);

        Path packageRoot = service.ensurePackageRoot(null);

        assertEquals(this.tempDir.resolve(ProjectArchiveService.PACKAGE_FOLDER_NAME), packageRoot);
        assertTrue(Files.isDirectory(packageRoot));
    }

    @Test
    void listPackageFilesReturnsZipFilesOnly() throws Exception {
        ProjectArchiveService service = new ProjectArchiveService(() -> this.tempDir);
        Path packageRoot = service.ensurePackageRoot(null);
        Files.write(packageRoot.resolve("roof.zip"), new byte[]{1, 2, 3});
        Files.writeString(packageRoot.resolve("notes.txt"), "ignore");

        var files = service.listPackageFiles(null);

        assertEquals(1, files.size());
        assertEquals("roof.zip", files.getFirst().fileName());
    }
}
