package io.github.luma.storage.repository;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchEntityChunkIndex;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.SectionFingerprint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.storage.ProjectLayout;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes current chunk-addressable patch payloads and their lightweight metadata.
 */
final class PatchPayloadWriter {

    private final PatchFrameCompression frameCompression = new PatchFrameCompression();
    private final PatchEntityChunkIndexLookup entityIndexLookup = new PatchEntityChunkIndexLookup();
    private final PatchPayloadMetadataBuilder metadataBuilder = new PatchPayloadMetadataBuilder();
    private final PatchSectionFrameCodec sectionFrameCodec = new PatchSectionFrameCodec();

    PatchMetadata writePayload(
            ProjectLayout layout,
            String patchId,
            String projectId,
            String versionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        Map<String, ChunkPayload> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            String key = chunkKey(change);
            grouped.computeIfAbsent(key, ignored -> new ChunkPayload()).blockChanges.add(change);
        }
        for (StoredEntityChange change : entityChanges) {
            String key = chunkKey(change);
            grouped.computeIfAbsent(key, ignored -> new ChunkPayload()).entityChanges.add(change);
        }

        List<Map.Entry<String, ChunkPayload>> sortedChunks = new ArrayList<>(grouped.entrySet());
        sortedChunks.sort(Comparator.comparing(Map.Entry<String, ChunkPayload>::getKey));
        LumaMod.LOGGER.info(
                "Writing patch payload {} with {} block changes and {} entity changes across {} chunks",
                patchId,
                changes.size(),
                entityChanges.size(),
                sortedChunks.size()
        );

        List<ChunkFrame> frames = new ArrayList<>();
        int chunkIndex = 0;
        for (Map.Entry<String, ChunkPayload> entry : sortedChunks) {
            String[] split = entry.getKey().split(":", 2);
            int chunkX = Integer.parseInt(split[0]);
            int chunkZ = Integer.parseInt(split[1]);

            List<StoredBlockChange> chunkChanges = new ArrayList<>(entry.getValue().blockChanges);
            chunkChanges.sort(Comparator.comparingInt(change -> packLocalPosition(change.pos())));
            List<StoredEntityChange> chunkEntityChanges = new ArrayList<>(entry.getValue().entityChanges);
            chunkEntityChanges.sort(Comparator.comparing(StoredEntityChange::entityId));

            byte[] chunkBytes = this.writeChunk(chunkX, chunkZ, chunkChanges, chunkEntityChanges);
            frames.add(new ChunkFrame(
                    chunkX,
                    chunkZ,
                    chunkChanges.size(),
                    chunkBytes.length,
                    this.frameCompression.compress(chunkBytes),
                    this.metadataBuilder.sectionFingerprints(chunkX, chunkZ, chunkChanges),
                    this.metadataBuilder.visibleChangeCount(chunkChanges),
                    this.metadataBuilder.visibleSectionFingerprints(chunkX, chunkZ, chunkChanges),
                    chunkEntityChanges.size()
            ));
            chunkIndex += 1;
            BackgroundThrottle.pauseEvery(chunkIndex, 8, 250_000L);
        }

        List<PatchChunkSlice> slices = new ArrayList<>();
        StorageIo.writeAtomically(layout.patchDataFile(patchId), output -> this.writeChunkAddressablePayload(output, frames, slices));
        List<PatchEntityChunkIndex> entityChunkIndex = this.entityIndexLookup.build(entityChanges);
        return new PatchMetadata(
                patchId,
                projectId,
                versionId,
                layout.patchDataFile(patchId).getFileName().toString(),
                List.copyOf(slices),
                new PatchStats(changes.size(), slices.size()),
                entityChunkIndex
        );
    }

    private byte[] writeChunk(
            int chunkX,
            int chunkZ,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws IOException {
        ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(chunkBuffer)) {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            output.writeInt(changes.size());
            this.sectionFrameCodec.writeSectionFrames(output, changes);
            this.writeEntityChanges(output, entityChanges);
        }
        return chunkBuffer.toByteArray();
    }

    private void writeEntityChanges(DataOutputStream output, List<StoredEntityChange> entityChanges) throws IOException {
        output.writeInt(entityChanges.size());
        for (StoredEntityChange change : entityChanges) {
            output.writeUTF(change.entityId());
            output.writeUTF(change.entityType());
            StorageIo.writeNullableCompound(output, change.oldValue() == null ? null : change.oldValue().copyTag());
            StorageIo.writeNullableCompound(output, change.newValue() == null ? null : change.newValue().copyTag());
        }
    }

    private void writeChunkAddressablePayload(
            OutputStream output,
            List<ChunkFrame> frames,
            List<PatchChunkSlice> slices
    ) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output))) {
            data.writeInt(PatchDataRepository.PAYLOAD_MAGIC);
            data.writeInt(PatchDataRepository.CURRENT_PAYLOAD_VERSION);
            data.writeInt(frames.size());
            long offset = 12L;
            for (ChunkFrame frame : frames) {
                byte[] frameHeader = this.writeFrameHeader(frame);
                slices.add(new PatchChunkSlice(
                        frame.chunkX(),
                        frame.chunkZ(),
                        frame.changeCount(),
                        offset,
                        frameHeader.length + frame.compressedBytes().length,
                        frame.sectionFingerprints(),
                        frame.visibleChangeCount(),
                        frame.visibleSectionFingerprints(),
                        true,
                        frame.entityCount()
                ));
                data.write(frameHeader);
                data.write(frame.compressedBytes());
                offset += frameHeader.length + frame.compressedBytes().length;
            }
        }
    }

    private byte[] writeFrameHeader(ChunkFrame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(frame.chunkX());
            output.writeInt(frame.chunkZ());
            output.writeInt(frame.sectionFingerprints().size());
            for (SectionFingerprint fingerprint : frame.sectionFingerprints()) {
                output.writeInt(fingerprint.sectionY());
                output.writeInt(fingerprint.changedCount());
                output.writeLong(fingerprint.xxHash64());
                output.writeUTF(fingerprint.sha256());
            }
            output.writeInt(frame.entityCount());
            output.writeInt(frame.uncompressedLength());
            output.writeInt(frame.compressedBytes().length);
        }
        return bytes.toByteArray();
    }

    private static String chunkKey(StoredBlockChange change) {
        return (change.pos().x() >> 4) + ":" + (change.pos().z() >> 4);
    }

    private static String chunkKey(StoredEntityChange change) {
        var chunk = change.chunk();
        return chunk.x() + ":" + chunk.z();
    }

    private static int packLocalPosition(BlockPoint pos) {
        int normalizedY = pos.y() - Short.MIN_VALUE;
        return (normalizedY << 8) | ((pos.z() & 15) << 4) | (pos.x() & 15);
    }

    private static final class ChunkPayload {

        private final List<StoredBlockChange> blockChanges = new ArrayList<>();
        private final List<StoredEntityChange> entityChanges = new ArrayList<>();
    }

    private record ChunkFrame(
            int chunkX,
            int chunkZ,
            int changeCount,
            int uncompressedLength,
            byte[] compressedBytes,
            List<SectionFingerprint> sectionFingerprints,
            int visibleChangeCount,
            List<SectionFingerprint> visibleSectionFingerprints,
            int entityCount
    ) {

        private ChunkFrame {
            sectionFingerprints = sectionFingerprints == null ? List.of() : List.copyOf(sectionFingerprints);
            visibleSectionFingerprints = visibleSectionFingerprints == null
                    ? List.of()
                    : List.copyOf(visibleSectionFingerprints);
        }
    }
}
