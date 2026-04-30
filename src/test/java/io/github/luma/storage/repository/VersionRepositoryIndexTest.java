package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionRepositoryIndexTest {

    @TempDir
    Path tempDir;

    private final VersionRepository repository = new VersionRepository();

    @Test
    void loadAllCreatesAndUsesFreshVersionIndex() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Indexed");
        this.repository.save(layout, version("v0001", "Manifest message", 0));
        String indexedJson = Files.readString(layout.versionIndexFile())
                .replace("Manifest message", "Indexed message");
        Files.writeString(layout.versionIndexFile(), indexedJson, StandardCharsets.UTF_8);

        List<ProjectVersion> versions = this.repository.loadAll(layout);

        assertEquals("Indexed message", versions.getFirst().message());
    }

    @Test
    void staleIndexRebuildsAfterManifestSizeOrMtimeChanges() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Stale");
        this.repository.save(layout, version("v0001", "Before", 0));
        Path manifest = layout.versionFile("v0001");
        Files.writeString(manifest, GsonProvider.gson().toJson(version("v0001", "After manifest change", 0)), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(manifest, FileTime.fromMillis(Files.getLastModifiedTime(manifest).toMillis() + 2000L));

        List<ProjectVersion> versions = this.repository.loadAll(layout);

        assertEquals("After manifest change", versions.getFirst().message());
        assertTrue(Files.readString(layout.versionIndexFile()).contains("After manifest change"));
    }

    @Test
    void corruptIndexIsIgnoredAndRebuilt() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Corrupt");
        this.repository.save(layout, version("v0001", "Manifest", 0));
        Files.writeString(layout.versionIndexFile(), "{not-json", StandardCharsets.UTF_8);

        List<ProjectVersion> versions = this.repository.loadAll(layout);

        assertEquals("Manifest", versions.getFirst().message());
        assertTrue(Files.readString(layout.versionIndexFile()).contains("Manifest"));
    }

    @Test
    void indexJsonIsNeverParsedAsVersionManifest() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Index Only");
        Files.createDirectories(layout.versionsDir());
        Files.writeString(layout.versionIndexFile(), "{\"entries\":[]}", StandardCharsets.UTF_8);

        assertTrue(this.repository.loadAll(layout).isEmpty());
    }

    private static ProjectVersion version(String id, String message, int offsetSeconds) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                "snapshot",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                message,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-04-28T08:00:00Z").plusSeconds(offsetSeconds)
        );
    }
}
