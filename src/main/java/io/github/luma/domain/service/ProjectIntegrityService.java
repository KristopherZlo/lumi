package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

public final class ProjectIntegrityService {

    private final ProjectService projectService = new ProjectService();
    private final io.github.luma.storage.repository.VersionRepository versionRepository = new io.github.luma.storage.repository.VersionRepository();

    public ProjectIntegrityReport inspect(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        var versions = this.versionRepository.loadAll(layout);
        if (versions.isEmpty()) {
            warnings.add("Project has no saved versions");
        }

        for (var version : versions) {
            if (version.snapshotId() != null && !version.snapshotId().isBlank() && !Files.exists(layout.snapshotsDir().resolve(version.snapshotId() + ".nbt.lz4"))) {
                errors.add("Missing snapshot file for " + version.id());
            }

            for (String patchId : version.patchIds()) {
                if (!Files.exists(layout.patchFile(patchId))) {
                    errors.add("Missing patch file " + patchId + " for " + version.id());
                }
            }
        }

        if (!Files.exists(layout.projectFile())) {
            errors.add("Missing project.json");
        }
        if (!Files.exists(layout.variantsFile())) {
            errors.add("Missing variants.json");
        }

        return new ProjectIntegrityReport(errors.isEmpty(), List.copyOf(warnings), List.copyOf(errors));
    }
}
