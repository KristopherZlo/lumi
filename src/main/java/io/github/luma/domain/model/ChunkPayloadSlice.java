package io.github.luma.domain.model;

import java.util.List;

public record ChunkPayloadSlice(
        int chunkX,
        int chunkZ,
        long dataOffsetBytes,
        int dataLengthBytes,
        List<SectionFingerprint> sectionFingerprints,
        List<ContentRef> contentRefs,
        int entityCount
) {

    public ChunkPayloadSlice {
        sectionFingerprints = sectionFingerprints == null ? List.of() : List.copyOf(sectionFingerprints);
        contentRefs = contentRefs == null ? List.of() : List.copyOf(contentRefs);
    }

    public ChunkPayloadSlice(
            int chunkX,
            int chunkZ,
            long dataOffsetBytes,
            int dataLengthBytes,
            List<SectionFingerprint> sectionFingerprints,
            int entityCount
    ) {
        this(chunkX, chunkZ, dataOffsetBytes, dataLengthBytes, sectionFingerprints, List.of(), entityCount);
    }

    public ChunkPayloadSlice(int chunkX, int chunkZ, long dataOffsetBytes, int dataLengthBytes) {
        this(chunkX, chunkZ, dataOffsetBytes, dataLengthBytes, List.of(), List.of(), 0);
    }

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }
}
