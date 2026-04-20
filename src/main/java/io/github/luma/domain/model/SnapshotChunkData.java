package io.github.luma.domain.model;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

public record SnapshotChunkData(
        int chunkX,
        int chunkZ,
        List<SnapshotSectionData> sections,
        Map<Integer, CompoundTag> blockEntities
) {

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }
}
