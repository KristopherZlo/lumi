package io.github.luma.storage.repository;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.io.OutputStreamWriter;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(ProjectSettings.DEFAULT_AUTO_CHECKPOINT_LARGE_CHANGE_THRESHOLD, loaded.settings().autoCheckpointLargeChangeThreshold());
    }

    @Test
    void loadsLegacyProjectIdAndBranchFields() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "LegacyWorld");
        this.repository.initializeLayout(layout);
        Files.writeString(layout.projectFile(), """
                {
                  "schemaVersion": 1,
                  "projectId": "26a07bfb-214b-43ae-ba74-6eba3580f12b",
                  "name": "GameTest Restore",
                  "dimensionId": "minecraft:overworld",
                  "mainBranchId": "main",
                  "activeBranchId": "main",
                  "settings": {
                    "autoVersionsEnabled": false,
                    "autoVersionMinutes": 15,
                    "sessionIdleSeconds": 300,
                    "snapshotEveryVersions": 5,
                    "snapshotVolumeThreshold": 32768,
                    "safetySnapshotBeforeRestore": true,
                    "previewGenerationEnabled": true,
                    "debugLoggingEnabled": false,
                    "autoCheckpointEnabled": false,
                    "workspaceHudEnabled": true
                  }
                }
                """, StandardCharsets.UTF_8);

        BuildProject loaded = this.repository.load(layout).orElseThrow();

        assertEquals("26a07bfb-214b-43ae-ba74-6eba3580f12b", loaded.id().toString());
        assertEquals("main", loaded.mainVariantId());
        assertEquals("main", loaded.activeVariantId());
        assertEquals(Instant.EPOCH, loaded.createdAt());
        assertEquals(Instant.EPOCH, loaded.updatedAt());
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
    void atomicWriteCleansTempFileWhenWriterFails() throws Exception {
        Path target = this.tempDir.resolve("project.json");

        assertThrows(IOException.class, () -> StorageIo.writeAtomically(target, output -> {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("boom");
        }));

        assertFalse(Files.exists(target));
        assertFalse(this.hasTempFiles());
    }

    @Test
    void atomicWriteAllowsWritersToCloseTheirWrapper() throws Exception {
        Path target = this.tempDir.resolve("project.json");

        StorageIo.writeAtomically(target, output -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write("ok");
            }
        });

        assertEquals("ok", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(this.hasTempFiles());
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

    private boolean hasTempFiles() throws IOException {
        try (var files = Files.list(this.tempDir)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }
}
