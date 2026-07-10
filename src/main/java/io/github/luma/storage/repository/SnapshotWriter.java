package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.ContentRef;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.SectionChangeMask;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameOutputStream;

public final class SnapshotWriter {

    private static final int MAGIC = 0x4C534E50;
    private static final int VERSION = 8;
    private static final String SNAPSHOT_SECTION_CONTENT = "snapshot-section";
    private final PayloadContentRepository contentRepository = new PayloadContentRepository();

    public void writeFile(Path snapshotFile, SnapshotData snapshot) throws IOException {
        StorageIo.writeAtomically(snapshotFile, output -> this.writeCompressed(output, snapshot, null));
    }

    public void writeFile(ProjectLayout layout, Path snapshotFile, SnapshotData snapshot) throws IOException {
        StorageIo.writeAtomically(snapshotFile, output -> this.writeCompressed(output, snapshot, layout));
    }

    public void writePreparedChunkFile(
            Path snapshotFile,
            String projectId,
            ChunkSnapshotPayload chunk,
            Instant now
    ) throws IOException {
        this.writePreparedChunkFile(snapshotFile, projectId, List.of(chunk), now);
    }

    public void writePreparedChunkFile(
            Path snapshotFile,
            String projectId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        this.writeFile(snapshotFile, this.materializePreparedSnapshot(projectId, chunks, now));
    }

    public void writePreparedChunkFile(
            ProjectLayout layout,
            Path snapshotFile,
            String projectId,
            ChunkSnapshotPayload chunk,
            Instant now
    ) throws IOException {
        this.writePreparedChunkFile(layout, snapshotFile, projectId, List.of(chunk), now);
    }

    public void writePreparedChunkFile(
            ProjectLayout layout,
            Path snapshotFile,
            String projectId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        SnapshotData snapshot = this.materializePreparedSnapshot(projectId, chunks, now);
        this.writeFile(layout, snapshotFile, snapshot);
    }

    public SnapshotRef writePreparedSnapshot(
            ProjectLayout layout,
            String projectId,
            String snapshotId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        return this.writePreparedSnapshot(layout, layout.snapshotFile(snapshotId), projectId, snapshotId, chunks, now);
    }

    public SnapshotRef writePreparedSnapshot(
            ProjectLayout layout,
            Path snapshotFile,
            String projectId,
            String snapshotId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) throws IOException {
        SnapshotData snapshot = this.materializePreparedSnapshot(projectId, chunks, now);
        this.writeFile(layout, snapshotFile, snapshot);
        return new SnapshotRef(
                snapshotId,
                projectId,
                snapshotFile.getFileName().toString(),
                snapshot.chunks().size(),
                Files.size(snapshotFile),
                now
        );
    }

    private void writeCompressed(OutputStream output, SnapshotData snapshot, ProjectLayout layout) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeUTF(snapshot.projectId());
            data.writeLong(snapshot.createdAt().toEpochMilli());
            data.writeInt(snapshot.minBuildHeight());
            data.writeInt(snapshot.maxBuildHeight());
            data.writeInt(snapshot.chunks().size());

            for (SnapshotChunkData chunk : snapshot.chunks()) {
                List<EncodedSection> sections = this.encodeSections(layout, chunk);
                byte[] chunkBytes = this.chunkBytes(chunk, sections);
                byte[] compressedBytes = this.compressFrame(chunkBytes);
                data.writeInt(chunk.chunkX());
                data.writeInt(chunk.chunkZ());
                this.writeSectionFingerprints(data, chunk, sections);
                data.writeInt(chunk.entitySnapshots().size());
                data.writeInt(chunkBytes.length);
                data.writeInt(compressedBytes.length);
                data.write(compressedBytes);
            }
        }
    }

    private byte[] chunkBytes(SnapshotChunkData chunk, List<EncodedSection> sections) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(chunk.chunkX());
            data.writeInt(chunk.chunkZ());
            data.writeInt(sections.size());
            data.writeInt(chunk.blockEntities().size());
            for (EncodedSection section : sections) {
                data.write(section.bytes());
            }
            for (Map.Entry<Integer, net.minecraft.nbt.CompoundTag> entry : chunk.blockEntities().entrySet()) {
                data.writeInt(entry.getKey());
                StorageIo.writeCompound(data, entry.getValue());
            }
            data.writeInt(chunk.entitySnapshots().size());
            for (EntityPayload entitySnapshot : chunk.entitySnapshots()) {
                StorageIo.writeCompound(data, entitySnapshot.copyTag());
            }
        }
        return bytes.toByteArray();
    }

    private void writeSectionFingerprints(
            DataOutputStream data,
            SnapshotChunkData chunk,
            List<EncodedSection> sections
    ) throws IOException {
        data.writeInt(sections.size());
        for (EncodedSection section : sections) {
            SectionFingerprint fingerprint = SectionFingerprint.fromBytes(
                    chunk.chunkX(),
                    chunk.chunkZ(),
                    section.sectionY(),
                    SectionChangeMask.ENTRY_COUNT,
                    section.bytes()
            );
            data.writeInt(fingerprint.sectionY());
            data.writeInt(fingerprint.changedCount());
            data.writeLong(fingerprint.xxHash64());
            data.writeUTF(fingerprint.sha256());
            this.writeContentRef(data, section.contentRef());
        }
    }

    private List<EncodedSection> encodeSections(ProjectLayout layout, SnapshotChunkData chunk) throws IOException {
        List<SnapshotSectionData> storedSections = storedSections(chunk);
        List<EncodedSection> encoded = new ArrayList<>(storedSections.size());
        for (SnapshotSectionData section : storedSections) {
            byte[] bytes = this.sectionBytes(section);
            ContentRef contentRef = layout == null
                    ? section.contentRef()
                    : this.contentRepository.writeContent(layout, SNAPSHOT_SECTION_CONTENT, bytes);
            encoded.add(new EncodedSection(section.sectionY(), bytes, contentRef));
        }
        return encoded;
    }

    private void writeContentRef(DataOutputStream data, ContentRef contentRef) throws IOException {
        boolean present = contentRef != null && !contentRef.sha256().isBlank();
        data.writeBoolean(present);
        if (!present) {
            return;
        }
        data.writeUTF(contentRef.sha256());
        data.writeUTF(contentRef.logicalKind());
        data.writeLong(contentRef.uncompressedBytes());
        data.writeLong(contentRef.compressedBytes());
    }

    private byte[] sectionBytes(SnapshotSectionData section) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            this.writeSection(data, section);
        }
        return bytes.toByteArray();
    }

    private void writeSection(DataOutputStream data, SnapshotSectionData section) throws IOException {
        data.writeInt(section.sectionY());
        data.writeInt(section.palette().size());
        for (var tag : section.palette()) {
            StorageIo.writeCompound(data, tag);
        }
        data.writeInt(section.bitsPerEntry());
        long[] packedStorage = section.packedStorage();
        data.writeInt(packedStorage.length);
        for (long packedLong : packedStorage) {
            data.writeLong(packedLong);
        }
    }

    private byte[] compressFrame(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream compressed = new LZ4FrameOutputStream(output)) {
            compressed.write(bytes);
        }
        return output.toByteArray();
    }

    private SnapshotData materializePreparedSnapshot(
            String projectId,
            Collection<ChunkSnapshotPayload> chunks,
            Instant now
    ) {
        List<ChunkSnapshotPayload> orderedChunks = List.copyOf(chunks == null ? List.<ChunkSnapshotPayload>of() : chunks);
        int minBuildHeight = orderedChunks.isEmpty() ? 0 : orderedChunks.getFirst().minBuildHeight();
        int maxBuildHeight = orderedChunks.isEmpty() ? 0 : orderedChunks.getFirst().maxBuildHeight();
        List<SnapshotChunkData> chunkData = new ArrayList<>(orderedChunks.size());
        for (ChunkSnapshotPayload chunk : orderedChunks) {
            minBuildHeight = Math.min(minBuildHeight, chunk.minBuildHeight());
            maxBuildHeight = Math.max(maxBuildHeight, chunk.maxBuildHeight());
            chunkData.add(this.materializePreparedChunk(chunk));
        }
        return new SnapshotData(projectId, now, minBuildHeight, maxBuildHeight, chunkData);
    }

    private SnapshotChunkData materializePreparedChunk(ChunkSnapshotPayload chunk) {
        List<SnapshotSectionData> sections = new ArrayList<>(chunk.sections().size());
        for (ChunkSectionSnapshotPayload section : chunk.sections()) {
            sections.add(new SnapshotSectionData(
                    section.sectionY(),
                    section.palette(),
                    section.bitsPerEntry(),
                    section.packedStorage()
            ));
        }
        return new SnapshotChunkData(
                chunk.chunkX(),
                chunk.chunkZ(),
                sections,
                chunk.blockEntities(),
                chunk.entitySnapshots()
        );
    }

    public static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }

    private static List<SnapshotSectionData> storedSections(SnapshotChunkData chunk) {
        if (chunk == null || chunk.sections().isEmpty()) {
            return List.of();
        }
        return chunk.sections().stream()
                .filter(section -> section != null && !section.palette().isEmpty())
                .toList();
    }

    private record EncodedSection(int sectionY, byte[] bytes, ContentRef contentRef) {
    }
}
