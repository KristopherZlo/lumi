package io.github.luma.domain.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Project-level spatial scope that must be reconciled against its saved head. */
public final class ProjectDirtyScope {

    private final String projectId;
    private final String variantId;
    private final String baseVersionId;
    private final LinkedHashSet<ChunkSectionPoint> blockSections;
    private final LinkedHashSet<ChunkPoint> entityChunks;

    public ProjectDirtyScope(
            String projectId,
            String variantId,
            String baseVersionId,
            Collection<ChunkSectionPoint> blockSections,
            Collection<ChunkPoint> entityChunks
    ) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("project id is required");
        }
        if (variantId == null || variantId.isBlank()) {
            throw new IllegalArgumentException("variant id is required");
        }
        this.projectId = projectId;
        this.variantId = variantId;
        this.baseVersionId = baseVersionId == null ? "" : baseVersionId;
        this.blockSections = copyBlockSections(blockSections);
        this.entityChunks = copyEntityChunks(entityChunks);
    }

    public static ProjectDirtyScope empty(String projectId, String variantId, String baseVersionId) {
        return new ProjectDirtyScope(projectId, variantId, baseVersionId, Set.of(), Set.of());
    }

    public String projectId() {
        return this.projectId;
    }

    public String variantId() {
        return this.variantId;
    }

    public String baseVersionId() {
        return this.baseVersionId;
    }

    public Set<ChunkSectionPoint> blockSections() {
        return Set.copyOf(this.blockSections);
    }

    public Set<ChunkPoint> entityChunks() {
        return Set.copyOf(this.entityChunks);
    }

    public boolean markBlockSection(ChunkSectionPoint section) {
        return section != null && this.blockSections.add(section);
    }

    public boolean markBlockSections(Collection<ChunkSectionPoint> sections) {
        boolean changed = false;
        for (ChunkSectionPoint section : sections == null ? Set.<ChunkSectionPoint>of() : sections) {
            changed |= this.markBlockSection(section);
        }
        return changed;
    }

    public boolean markEntityChunk(ChunkPoint chunk) {
        return chunk != null && this.entityChunks.add(chunk);
    }

    public boolean isEmpty() {
        return this.blockSections.isEmpty() && this.entityChunks.isEmpty();
    }

    public ProjectDirtyScope copy() {
        return new ProjectDirtyScope(
                this.projectId,
                this.variantId,
                this.baseVersionId,
                this.blockSections,
                this.entityChunks
        );
    }

    private static LinkedHashSet<ChunkSectionPoint> copyBlockSections(Collection<ChunkSectionPoint> sections) {
        LinkedHashSet<ChunkSectionPoint> copy = new LinkedHashSet<>();
        if (sections != null) {
            sections.stream().filter(java.util.Objects::nonNull).forEach(copy::add);
        }
        return copy;
    }

    private static LinkedHashSet<ChunkPoint> copyEntityChunks(Collection<ChunkPoint> chunks) {
        LinkedHashSet<ChunkPoint> copy = new LinkedHashSet<>();
        if (chunks != null) {
            chunks.stream().filter(java.util.Objects::nonNull).forEach(copy::add);
        }
        return copy;
    }
}
