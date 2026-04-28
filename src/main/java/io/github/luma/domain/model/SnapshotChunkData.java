package io.github.luma.domain.model;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

public record SnapshotChunkData(
        int chunkX,
        int chunkZ,
        List<SnapshotSectionData> sections,
        Map<Integer, CompoundTag> blockEntities,
        List<EntityPayload> entitySnapshots
) {

    public SnapshotChunkData(
            int chunkX,
            int chunkZ,
            List<SnapshotSectionData> sections,
            Map<Integer, CompoundTag> blockEntities
    ) {
        this(chunkX, chunkZ, sections, blockEntities, List.of());
    }

    public SnapshotChunkData {
        sections = sections == null ? List.of() : List.copyOf(sections);
        blockEntities = blockEntities == null ? Map.of() : Map.copyOf(blockEntities);
        entitySnapshots = copyEntitySnapshots(entitySnapshots);
    }

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }

    private static List<EntityPayload> copyEntitySnapshots(List<EntityPayload> entitySnapshots) {
        if (entitySnapshots == null || entitySnapshots.isEmpty()) {
            return List.of();
        }
        return entitySnapshots.stream()
                .map(payload -> payload == null ? new EntityPayload(null) : new EntityPayload(payload.copyTag()))
                .toList();
    }
}
