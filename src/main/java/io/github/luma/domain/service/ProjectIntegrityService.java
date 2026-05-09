package io.github.luma.domain.service;

import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.minecraft.server.MinecraftServer;

public final class ProjectIntegrityService {

    private static final int PATCH_MAGIC = 0x4C504154;
    private static final int PATCH_CHUNK_ADDRESSABLE_V6 = 6;
    private static final int PATCH_SECTION_FRAME_V7 = 7;
    private static final int PATCH_HIDDEN_MASK_V8 = 8;
    private static final int PATCH_SECTION_FINGERPRINT_V9 = 9;
    private static final int SNAPSHOT_MAGIC = 0x4C534E50;
    private static final int SNAPSHOT_ADDRESSABLE_V6 = 6;

    private final ProjectService projectService = new ProjectService();
    private final io.github.luma.storage.repository.VersionRepository versionRepository = new io.github.luma.storage.repository.VersionRepository();
    private final io.github.luma.storage.repository.PatchMetaRepository patchMetaRepository = new io.github.luma.storage.repository.PatchMetaRepository();

    public ProjectIntegrityReport inspect(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        return this.inspect(layout);
    }

    ProjectIntegrityReport inspect(ProjectLayout layout) throws IOException {
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
                } else if (!this.hasReadableSnapshotHeader(layout, version.snapshotId())) {
                    errors.add("Corrupt snapshot header for " + version.id());
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
                } else if (!this.hasReadablePatchHeader(layout, patchId)) {
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

    private boolean hasReadablePatchHeader(ProjectLayout layout, String patchId) {
        try {
            if (Files.size(layout.patchDataFile(patchId)) < Integer.BYTES * 2L) {
                return false;
            }
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(layout.patchDataFile(patchId))))) {
                int magic = input.readInt();
                int version = input.readInt();
                if (magic == PATCH_MAGIC) {
                    return version == PATCH_CHUNK_ADDRESSABLE_V6
                            || version == PATCH_SECTION_FRAME_V7
                            || version == PATCH_HIDDEN_MASK_V8
                            || version == PATCH_SECTION_FINGERPRINT_V9;
                }
            }
            try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                    new BufferedInputStream(Files.newInputStream(layout.patchDataFile(patchId)))
            ))) {
                int magic = input.readInt();
                int version = input.readInt();
                return magic == PATCH_MAGIC && (version == 3 || version == 4 || version == 5);
            }
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean hasReadableSnapshotHeader(ProjectLayout layout, String snapshotId) {
        Path snapshotFile = layout.snapshotFile(snapshotId);
        try {
            if (Files.size(snapshotFile) < Integer.BYTES * 2L) {
                return false;
            }
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
                int magic = input.readInt();
                int version = input.readInt();
                if (magic == SNAPSHOT_MAGIC) {
                    return version == SNAPSHOT_ADDRESSABLE_V6;
                }
            }
        } catch (IOException exception) {
            return false;
        }
        try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile))
        ))) {
            int magic = input.readInt();
            int version = input.readInt();
            return magic == SNAPSHOT_MAGIC && (version == 3 || version == 4 || version == 5);
        } catch (IOException exception) {
            return false;
        }
    }
}
