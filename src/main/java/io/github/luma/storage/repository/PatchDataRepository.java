package io.github.luma.storage.repository;

import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PatchDataRepository {

    static final int PAYLOAD_MAGIC = 0x4C504154;
    private static final int VERSION = 9;
    static final int CURRENT_PAYLOAD_VERSION = VERSION;
    static final int HIDDEN_MASK_PAYLOAD_VERSION = 8;
    static final int CHUNK_ADDRESSABLE_PAYLOAD_VERSION = 6;
    static final int SECTION_FRAME_PAYLOAD_VERSION = 7;
    private final PatchEntityChunkIndexLookup entityIndexLookup = new PatchEntityChunkIndexLookup();
    private final PatchSectionFrameCodec sectionFrameCodec = new PatchSectionFrameCodec();
    private final PatchPayloadWriter payloadWriter = new PatchPayloadWriter();
    private final PatchPayloadReader payloadReader = new PatchPayloadReader();

    public PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes
    ) throws IOException {
        return this.writePayload(layout, patchId, projectId, versionId, changes, List.of());
    }

    public PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws IOException {
        return this.payloadWriter.writePayload(layout, patchId, projectId, versionId, changes, entityChanges);
    }

    public List<StoredBlockChange> loadChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        return this.loadWorldChanges(layout, metadata).blockChanges();
    }

    public PatchWorldChanges loadWorldChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        if (metadata == null) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        return this.payloadReader.loadWorldChanges(layout.patchDataFile(metadata.id()), metadata);
    }

    public PatchWorldChanges loadWorldChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        if (metadata == null || chunks == null || chunks.isEmpty()) {
            return new PatchWorldChanges(List.of(), List.of());
        }

        return this.payloadReader.loadWorldChanges(layout.patchDataFile(metadata.id()), metadata, chunks);
    }

    public PatchSectionWorldChanges loadSectionWorldChanges(ProjectLayout layout, PatchMetadata metadata) throws IOException {
        if (metadata == null) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        return this.payloadReader.loadSectionWorldChanges(layout.patchDataFile(metadata.id()), metadata);
    }

    public PatchSectionWorldChanges loadSectionWorldChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<SectionFingerprint> sections
    ) throws IOException {
        if (metadata == null || sections == null || sections.isEmpty()) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        Set<String> requestedSections = new HashSet<>();
        Set<ChunkPoint> requestedChunks = new HashSet<>();
        for (SectionFingerprint section : sections) {
            if (section == null) {
                continue;
            }
            requestedSections.add(sectionKey(section.chunkX(), section.chunkZ(), section.sectionY()));
            requestedChunks.add(section.chunk());
        }
        if (requestedSections.isEmpty()) {
            return new PatchSectionWorldChanges(List.of(), List.of());
        }
        PatchSectionWorldChanges selectedChunks = this.sectionFrameCodec.toSectionWorldChanges(
                this.loadWorldChanges(layout, metadata, requestedChunks)
        );
        return new PatchSectionWorldChanges(
                selectedChunks.sectionFrames().stream()
                        .filter(frame -> requestedSections.contains(sectionKey(frame.chunkX(), frame.chunkZ(), frame.sectionY())))
                        .toList(),
                selectedChunks.entityChanges()
        );
    }

    public PatchWorldChanges loadWorldChangesForSections(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<SectionFingerprint> sections
    ) throws IOException {
        PatchSectionWorldChanges sectionChanges = this.loadSectionWorldChanges(layout, metadata, sections);
        List<StoredBlockChange> changes = new ArrayList<>();
        for (PatchSectionFrame frame : sectionChanges.sectionFrames()) {
            changes.addAll(this.sectionFrameCodec.toStoredChanges(frame));
        }
        return new PatchWorldChanges(changes, sectionChanges.entityChanges());
    }

    public List<StoredEntityChange> loadEntityChanges(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<String> entityIds
    ) throws IOException {
        Set<String> requestedIds = new HashSet<>();
        for (String entityId : entityIds == null ? List.<String>of() : entityIds) {
            if (entityId != null && !entityId.isBlank()) {
                requestedIds.add(entityId);
            }
        }
        if (metadata == null || requestedIds.isEmpty()) {
            return List.of();
        }
        Set<ChunkPoint> frameChunks = this.entityIndexLookup.frameChunksForEntityIds(metadata, requestedIds);
        if (!metadata.entityChunkIndex().isEmpty() && frameChunks.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = frameChunks.isEmpty()
                ? this.loadWorldChanges(layout, metadata).entityChanges()
                : this.loadWorldChanges(layout, metadata, frameChunks).entityChanges();
        return entityChanges.stream()
                .filter(change -> requestedIds.contains(change.entityId()))
                .toList();
    }

    public List<StoredEntityChange> loadEntityChangesForChunks(
            ProjectLayout layout,
            PatchMetadata metadata,
            Collection<ChunkPoint> chunks
    ) throws IOException {
        Set<ChunkPoint> requestedChunks = new HashSet<>(chunks == null ? List.<ChunkPoint>of() : chunks);
        if (metadata == null || requestedChunks.isEmpty()) {
            return List.of();
        }
        Set<ChunkPoint> frameChunks = this.entityIndexLookup.frameChunksForEntityChunks(metadata, requestedChunks);
        if (!metadata.entityChunkIndex().isEmpty() && frameChunks.isEmpty()) {
            return List.of();
        }
        List<StoredEntityChange> entityChanges = frameChunks.isEmpty()
                ? this.loadWorldChanges(layout, metadata).entityChanges()
                : this.loadWorldChanges(layout, metadata, frameChunks).entityChanges();
        return entityChanges.stream()
                .filter(change -> this.entityIndexLookup.touchesAnyChunk(change, requestedChunks))
                .toList();
    }

    private static String sectionKey(int chunkX, int chunkZ, int sectionY) {
        return chunkX + ":" + chunkZ + ":" + sectionY;
    }
}
