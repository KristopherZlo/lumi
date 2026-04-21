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
        Map<Integer, CompoundTag> blockEntities
) {

    public ChunkSnapshotPayload {
        sections = sections == null ? List.of() : List.copyOf(sections);
        blockEntities = copyBlockEntities(blockEntities);
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
}
