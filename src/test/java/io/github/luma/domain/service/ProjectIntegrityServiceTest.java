package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIntegrityServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectIntegrityService service = new ProjectIntegrityService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();

    @Test
    void reportsCorruptPatchAndSnapshotHeadersWithoutFullRestore() throws Exception {
        ProjectLayout layout = this.layout();
        this.writeProjectMetadata(layout);
        this.versionRepository.save(layout, version("v0001", "snapshot-corrupt", List.of("patch-corrupt")));
        Files.createDirectories(layout.patchesDir());
        Files.writeString(layout.patchMetaFile("patch-corrupt"), "{}", StandardCharsets.UTF_8);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(layout.patchDataFile("patch-corrupt")))) {
            output.writeInt(0x4C504154);
            output.writeInt(999);
        }
        Files.createDirectories(layout.snapshotsDir());
        try (DataOutputStream output = new DataOutputStream(new LZ4FrameOutputStream(
                new BufferedOutputStream(Files.newOutputStream(layout.snapshotFile("snapshot-corrupt")))
        ))) {
            output.writeInt(0);
            output.writeInt(5);
        }

        ProjectIntegrityReport report = this.service.inspect(layout);

        assertTrue(report.errors().stream().anyMatch(error -> error.contains("Corrupt patch payload header patch-corrupt")));
        assertTrue(report.errors().stream().anyMatch(error -> error.contains("Corrupt snapshot header")));
    }

    @Test
    void warnsOnCorruptVersionIndex() throws Exception {
        ProjectLayout layout = this.layout();
        this.writeProjectMetadata(layout);
        this.versionRepository.save(layout, version("v0001", "", List.of()));
        Files.writeString(layout.versionIndexFile(), "{not-json", StandardCharsets.UTF_8);

        ProjectIntegrityReport report = this.service.inspect(layout);

        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("Version index")));
    }

    private ProjectLayout layout() {
        return ProjectLayout.of(this.tempDir, "Integrity");
    }

    private void writeProjectMetadata(ProjectLayout layout) throws Exception {
        BuildProject project = BuildProject.createWorldWorkspace(
                "Integrity",
                "minecraft:overworld",
                Instant.parse("2026-04-28T08:00:00Z")
        );
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(ProjectVariant.main("v0001", Instant.parse("2026-04-28T08:00:00Z"))));
    }

    private static ProjectVersion version(String id, String snapshotId, List<String> patchIds) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                snapshotId,
                patchIds,
                VersionKind.MANUAL,
                "tester",
                "Version",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-04-28T08:00:00Z")
        );
    }
}
