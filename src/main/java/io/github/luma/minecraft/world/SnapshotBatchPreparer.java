package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Converts persisted snapshot payloads into Minecraft-ready chunk apply batches.
 */
public final class SnapshotBatchPreparer {

    private static final CompoundTag AIR_TAG = createAirTag();

    public List<PreparedChunkBatch> prepare(SnapshotData snapshot, ServerLevel level) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            batches.add(this.prepareChunk(snapshot, chunk, level));
        }
        return batches;
    }

    private PreparedChunkBatch prepareChunk(SnapshotData snapshot, SnapshotChunkData chunk, ServerLevel level) throws IOException {
        Map<Integer, SnapshotSectionData> sections = new HashMap<>();
        for (SnapshotSectionData section : chunk.sections()) {
            sections.put(section.sectionY(), section);
        }

        List<PreparedBlockPlacement> placements = new ArrayList<>();
        int minSection = Math.floorDiv(snapshot.minBuildHeight(), 16);
        int maxSection = Math.floorDiv(snapshot.maxBuildHeight(), 16);
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            SnapshotSectionData section = sections.get(sectionY);
            int sectionBaseY = sectionY << 4;
            int minY = Math.max(snapshot.minBuildHeight(), sectionBaseY);
            int maxY = Math.min(snapshot.maxBuildHeight(), sectionBaseY + 15);
            if (minY > maxY) {
                continue;
            }

            for (int y = minY; y <= maxY; y++) {
                int localY = y - sectionBaseY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        int stateIndex = section == null
                                ? 0
                                : section.paletteIndexes()[(localY << 8) | (localZ << 4) | localX];
                        CompoundTag stateTag = section == null
                                ? AIR_TAG
                                : section.palette().get(stateIndex);
                        BlockPos pos = new BlockPos((chunk.chunkX() << 4) + localX, y, (chunk.chunkZ() << 4) + localZ);
                        placements.add(new PreparedBlockPlacement(
                                pos,
                                BlockStateNbtCodec.deserializeBlockState(level, stateTag),
                                this.readBlockEntity(chunk, snapshot.minBuildHeight(), y, localX, localZ)
                        ));
                    }
                }
            }
        }
        return new PreparedChunkBatch(
                new ChunkPoint(chunk.chunkX(), chunk.chunkZ()),
                placements,
                this.prepareEntitySnapshots(chunk.entitySnapshots())
        );
    }

    private CompoundTag readBlockEntity(SnapshotChunkData chunk, int minBuildHeight, int y, int localX, int localZ) {
        CompoundTag tag = chunk.blockEntities().get(
                packVerticalIndex(y - minBuildHeight, localX, localZ)
        );
        return tag == null ? null : tag.copy();
    }

    private static int packVerticalIndex(int relativeY, int localX, int localZ) {
        return (relativeY << 8) | (localZ << 4) | localX;
    }

    private EntityBatch prepareEntitySnapshots(List<EntityPayload> entitySnapshots) {
        if (entitySnapshots == null || entitySnapshots.isEmpty()) {
            return EntityBatch.empty();
        }
        return new EntityBatch(
                List.of(),
                List.of(),
                entitySnapshots.stream()
                        .map(EntityPayload::copyTag)
                        .toList()
        );
    }

    private static CompoundTag createAirTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }
}
