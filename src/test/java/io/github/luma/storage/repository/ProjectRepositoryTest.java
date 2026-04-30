package io.github.luma.storage.repository;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepositoryTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository repository = new ProjectRepository();

    @Test
    void findsProjectsStoredUnderSanitizedFolderNames() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "My:Project");
        BuildProject project = BuildProject.createWorldWorkspace(
                "My:Project",
                "minecraft:overworld",
                Instant.parse("2026-04-28T08:00:00Z")
        );
        this.repository.save(layout, project);

        ProjectLayout found = this.repository.findLayoutByProjectName(this.tempDir, "My:Project").orElseThrow();

        assertEquals(layout.root(), found.root());
    }

    @Test
    void missingProjectsRootHasNoLayoutMatches() throws Exception {
        assertTrue(this.repository.findLayoutByProjectName(this.tempDir.resolve("missing"), "World").isEmpty());
    }

    @Test
    void legacyProjectWithoutAutoCheckpointSettingKeepsItDisabled() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Legacy");
        BuildProject project = BuildProject.createWorldWorkspace(
                "Legacy",
                "minecraft:overworld",
                Instant.parse("2026-04-28T08:00:00Z")
        );
        this.repository.initializeLayout(layout);
        String json = GsonProvider.gson()
                .toJson(project)
                .replace(",\"autoCheckpointEnabled\":false", "");
        Files.writeString(layout.projectFile(), json, StandardCharsets.UTF_8);

        BuildProject loaded = this.repository.load(layout).orElseThrow();

        assertFalse(loaded.settings().autoCheckpointEnabled());
    }

    @Test
    void savesProjectMetadataAtomicallyWithoutLeavingTempFile() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Atomic Project");
        BuildProject project = BuildProject.createWorldWorkspace(
                "Atomic Project",
                "minecraft:overworld",
                Instant.parse("2026-04-28T08:00:00Z")
        );

        this.repository.save(layout, project);

        assertEquals(project.id(), this.repository.load(layout).orElseThrow().id());
        assertFalse(Files.exists(layout.projectFile().resolveSibling("project.json.tmp")));
        try (var files = Files.list(layout.root())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("project.json.")
                    && path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void atomicWriteRetriesTransientAccessDeniedMoveFailures() throws Exception {
        Path target = this.tempDir.resolve("project.json");
        AtomicInteger attempts = new AtomicInteger();

        StorageIo.writeAtomically(
                target,
                output -> output.write("ok".getBytes(StandardCharsets.UTF_8)),
                (source, destination, options) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new AccessDeniedException(source.toString(), destination.toString(), "locked");
                    }
                    return Files.move(source, destination, options);
                },
                ignored -> {
                }
        );

        assertEquals(2, attempts.get());
        assertEquals("ok", Files.readString(target, StandardCharsets.UTF_8));
        try (var files = Files.list(this.tempDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void atomicWriteFallsBackWhenAtomicMoveIsUnsupported() throws Exception {
        Path target = this.tempDir.resolve("project.json");
        AtomicInteger attempts = new AtomicInteger();

        StorageIo.writeAtomically(
                target,
                output -> output.write("ok".getBytes(StandardCharsets.UTF_8)),
                (source, destination, options) -> {
                    attempts.incrementAndGet();
                    for (StandardCopyOption option : options) {
                        if (option == StandardCopyOption.ATOMIC_MOVE) {
                            throw new java.nio.file.AtomicMoveNotSupportedException(
                                    source.toString(),
                                    destination.toString(),
                                    "unsupported"
                            );
                        }
                    }
                    return Files.move(source, destination, options);
                },
                ignored -> {
                }
        );

        assertEquals(2, attempts.get());
        assertEquals("ok", Files.readString(target, StandardCharsets.UTF_8));
    }
}
