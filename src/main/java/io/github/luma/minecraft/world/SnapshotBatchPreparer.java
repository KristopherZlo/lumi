package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts persisted snapshot payloads into Minecraft-ready chunk apply batches.
 */
public final class SnapshotBatchPreparer {

    private static final CompoundTag AIR_TAG = createAirTag();
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier = new SectionApplySafetyClassifier();
    private final Supplier<BlockStateDecoder> blockStateDecoderFactory;

    public SnapshotBatchPreparer() {
        this(BlockStatePaletteDecoder::new);
    }

    SnapshotBatchPreparer(BlockStateDecoder blockStateDecoder) {
        this(() -> blockStateDecoder);
    }

    private SnapshotBatchPreparer(Supplier<BlockStateDecoder> blockStateDecoderFactory) {
        this.blockStateDecoderFactory = blockStateDecoderFactory;
    }

    public List<PreparedChunkBatch> prepare(SnapshotData snapshot, ServerLevel level) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        BlockState airState = blockStateDecoder.decode(level, AIR_TAG);
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            batches.add(this.prepareChunk(snapshot, chunk, level, blockStateDecoder, airState));
        }
        return batches;
    }

    private PreparedChunkBatch prepareChunk(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            ServerLevel level,
            BlockStateDecoder blockStateDecoder,
            BlockState airState
    ) throws IOException {
        Map<Integer, SnapshotSectionData> sections = new HashMap<>();
        for (SnapshotSectionData section : chunk.sections()) {
            sections.put(section.sectionY(), section);
        }

        List<PreparedSectionApplyBatch> nativeSections = new ArrayList<>();
        ChunkPoint chunkPoint = new ChunkPoint(chunk.chunkX(), chunk.chunkZ());
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

            LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(sectionY);
            BlockState[] decodedPalette = section == null
                    ? null
                    : this.decodePalette(level, section.palette(), blockStateDecoder);
            for (int y = minY; y <= maxY; y++) {
                int localY = y - sectionBaseY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        int stateIndex = section == null
                                ? 0
                                : section.paletteIndexes()[(localY << 8) | (localZ << 4) | localX];
                        builder.set(
                                localX,
                                localY,
                                localZ,
                                section == null ? airState : decodedPalette[stateIndex],
                                this.readBlockEntity(chunk, snapshot.minBuildHeight(), y, localX, localZ)
                        );
                    }
                }
            }
            LumiSectionBuffer buffer = builder.build();
            nativeSections.add(new PreparedSectionApplyBatch(
                    chunkPoint,
                    sectionY,
                    buffer,
                    this.sectionApplySafetyClassifier.classify(buffer, true),
                    true
            ));
        }
        return new PreparedChunkBatch(
                chunkPoint,
                List.of(),
                nativeSections,
                this.prepareEntitySnapshots(chunk.entitySnapshots())
        );
    }

    private BlockState[] decodePalette(
            ServerLevel level,
            List<CompoundTag> palette,
            BlockStateDecoder blockStateDecoder
    ) throws IOException {
        BlockState[] decoded = new BlockState[palette == null ? 0 : palette.size()];
        Map<CompoundTag, BlockState> sectionCache = new LinkedHashMap<>();
        for (int index = 0; index < decoded.length; index++) {
            CompoundTag tag = palette.get(index);
            CompoundTag key = tag == null ? new CompoundTag() : tag.copy();
            BlockState state;
            if (sectionCache.containsKey(key)) {
                state = sectionCache.get(key);
            } else {
                state = blockStateDecoder.decode(level, tag);
                sectionCache.put(key, state);
            }
            decoded[index] = state;
        }
        return decoded;
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
