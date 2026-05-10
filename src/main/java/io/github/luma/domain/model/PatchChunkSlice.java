package io.github.luma.domain.model;

import java.util.List;

public record PatchChunkSlice(
        int chunkX,
        int chunkZ,
        int changeCount,
        long dataOffsetBytes,
        int dataLengthBytes,
        List<SectionFingerprint> sectionFingerprints,
        int visibleChangeCount,
        List<SectionFingerprint> visibleSectionFingerprints,
        boolean visibleSectionIndexAvailable,
        int entityCount
) {

    public PatchChunkSlice {
        sectionFingerprints = sectionFingerprints == null ? List.of() : List.copyOf(sectionFingerprints);
        visibleSectionFingerprints = visibleSectionFingerprints == null
                ? List.of()
                : List.copyOf(visibleSectionFingerprints);
    }

    public PatchChunkSlice(
            int chunkX,
            int chunkZ,
            int changeCount,
            long dataOffsetBytes,
            int dataLengthBytes,
            List<SectionFingerprint> sectionFingerprints,
            int entityCount
    ) {
        this(chunkX, chunkZ, changeCount, dataOffsetBytes, dataLengthBytes, sectionFingerprints, 0, List.of(), false, entityCount);
    }

    public PatchChunkSlice(
            int chunkX,
            int chunkZ,
            int changeCount,
            long dataOffsetBytes,
            int dataLengthBytes
    ) {
        this(chunkX, chunkZ, changeCount, dataOffsetBytes, dataLengthBytes, List.of(), 0, List.of(), false, 0);
    }

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }
}
