package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Atomic persistence for the compact project-level dirty scope sidecar. */
public final class ProjectDirtyScopeRepository {

    private static final int MAGIC = 0x4C445343;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 1_000_000;

    public void save(ProjectLayout layout, ProjectDirtyScope scope) throws IOException {
        if (layout == null || scope == null) {
            throw new IllegalArgumentException("layout and dirty scope are required");
        }
        StorageIo.writeAtomically(layout.projectDirtyScopeFile(), output -> {
            try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeUTF(scope.projectId());
                data.writeUTF(scope.variantId());
                data.writeUTF(scope.baseVersionId());
                data.writeInt(scope.blockSections().size());
                for (ChunkSectionPoint section : scope.blockSections()) {
                    data.writeInt(section.chunkX());
                    data.writeInt(section.chunkZ());
                    data.writeInt(section.sectionY());
                }
                data.writeInt(scope.entityChunks().size());
                for (ChunkPoint chunk : scope.entityChunks()) {
                    data.writeInt(chunk.x());
                    data.writeInt(chunk.z());
                }
            }
        });
    }

    public Optional<ProjectDirtyScope> load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.projectDirtyScopeFile())) {
            return Optional.empty();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(layout.projectDirtyScopeFile())
        ))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported project dirty scope format");
            }
            String projectId = input.readUTF();
            String variantId = input.readUTF();
            String baseVersionId = input.readUTF();
            int blockCount = StorageLimits.requireLength("dirty block section", input.readInt(), MAX_ENTRIES);
            List<ChunkSectionPoint> sections = new ArrayList<>(blockCount);
            for (int index = 0; index < blockCount; index++) {
                sections.add(new ChunkSectionPoint(input.readInt(), input.readInt(), input.readInt()));
            }
            int entityCount = StorageLimits.requireLength("dirty entity chunk", input.readInt(), MAX_ENTRIES);
            List<ChunkPoint> entityChunks = new ArrayList<>(entityCount);
            for (int index = 0; index < entityCount; index++) {
                entityChunks.add(new ChunkPoint(input.readInt(), input.readInt()));
            }
            return Optional.of(new ProjectDirtyScope(
                    projectId,
                    variantId,
                    baseVersionId,
                    sections,
                    entityChunks
            ));
        }
    }

    public void delete(ProjectLayout layout) throws IOException {
        Files.deleteIfExists(layout.projectDirtyScopeFile());
    }
}
