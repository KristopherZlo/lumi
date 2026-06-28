package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class ProjectIntegrityRepository {

    private final VersionRepository versionRepository = new VersionRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchPayloadReader patchPayloadReader = new PatchPayloadReader();
    private final SnapshotReader snapshotReader = new SnapshotReader();

    public ProjectIntegrityReport inspect(ProjectLayout layout) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (!this.versionRepository.isVersionIndexFresh(layout)) {
            warnings.add("Version index is missing, stale, or corrupt");
        }

        var versions = this.versionRepository.loadAll(layout);
        if (versions.isEmpty()) {
            warnings.add("Project has no saved versions");
        }

        for (var version : versions) {
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                if (!Files.exists(layout.snapshotFile(version.snapshotId()))) {
                    errors.add("Missing snapshot file for " + version.id());
                } else if (!this.snapshotReader.hasReadableHeader(layout.snapshotFile(version.snapshotId()))) {
                    errors.add("Corrupt snapshot header for " + version.id());
                }
            }

            if (version.entityCheckpointId() != null && !version.entityCheckpointId().isBlank()) {
                if (!Files.exists(layout.entityCheckpointFile(version.entityCheckpointId()))) {
                    errors.add("Missing entity checkpoint file for " + version.id());
                } else if (!this.snapshotReader.hasReadableHeader(layout.entityCheckpointFile(version.entityCheckpointId()))) {
                    errors.add("Corrupt entity checkpoint header for " + version.id());
                }
            }

            for (String patchId : version.patchIds()) {
                if (!Files.exists(layout.patchMetaFile(patchId))) {
                    errors.add("Missing patch metadata " + patchId + " for " + version.id());
                } else if (!this.hasReadablePatchMetadata(layout, patchId)) {
                    errors.add("Corrupt patch metadata " + patchId + " for " + version.id());
                }
                if (!Files.exists(layout.patchDataFile(patchId))) {
                    errors.add("Missing patch payload " + patchId + " for " + version.id());
                } else if (!this.patchPayloadReader.hasReadablePayloadHeader(layout.patchDataFile(patchId))) {
                    errors.add("Corrupt patch payload header " + patchId + " for " + version.id());
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

    private boolean hasReadablePatchMetadata(ProjectLayout layout, String patchId) {
        try {
            return this.patchMetaRepository.load(layout, patchId).isPresent();
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
