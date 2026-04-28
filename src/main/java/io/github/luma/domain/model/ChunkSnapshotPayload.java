package io.github.luma.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * Immutable runtime payload for one copied chunk snapshot.
 *
 * <p>The payload mirrors the baseline snapshot format closely enough that it
 * can be persisted off-thread, while still carrying the compact packed section
 * storage used for faster live capture and reconciliation.
 */
public record ChunkSnapshotPayload(
        int chunkX,
        int chunkZ,
        int minBuildHeight,
        int maxBuildHeight,
        List<ChunkSectionSnapshotPayload> sections,
        Map<Integer, CompoundTag> blockEntities,
        List<EntityPayload> entitySnapshots
) {

    public ChunkSnapshotPayload(
            int chunkX,
            int chunkZ,
            int minBuildHeight,
            int maxBuildHeight,
            List<ChunkSectionSnapshotPayload> sections,
            Map<Integer, CompoundTag> blockEntities
    ) {
        this(chunkX, chunkZ, minBuildHeight, maxBuildHeight, sections, blockEntities, List.of());
    }

    public ChunkSnapshotPayload {
        sections = sections == null ? List.of() : List.copyOf(sections);
        blockEntities = copyBlockEntities(blockEntities);
        entitySnapshots = copyEntitySnapshots(entitySnapshots);
    }

    public ChunkPoint chunk() {
        return new ChunkPoint(this.chunkX, this.chunkZ);
    }

    @Override
    public List<ChunkSectionSnapshotPayload> sections() {
        return this.sections;
    }

    @Override
    public Map<Integer, CompoundTag> blockEntities() {
        return this.blockEntities;
    }

    @Override
    public List<EntityPayload> entitySnapshots() {
        return this.entitySnapshots;
    }

    private static Map<Integer, CompoundTag> copyBlockEntities(Map<Integer, CompoundTag> blockEntities) {
        if (blockEntities == null || blockEntities.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<Integer, CompoundTag> copied = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
            copied.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
        }
        return Map.copyOf(copied);
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
