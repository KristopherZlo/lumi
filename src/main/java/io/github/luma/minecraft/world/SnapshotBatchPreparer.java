package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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
        return this.prepare(snapshot, level, List.of());
    }

    public List<PreparedChunkBatch> prepare(
            SnapshotData snapshot,
            ServerLevel level,
            Collection<String> excludedEntityTypes
    ) throws IOException {
        List<PreparedChunkBatch> batches = new ArrayList<>();
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        BlockState airState = blockStateDecoder.decode(level, AIR_TAG);
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            batches.add(this.prepareChunk(snapshot, chunk, level, blockStateDecoder, airState, excludedEntityTypes));
        }
        return batches;
    }

    public List<PreparedChunkBatch> preparePositions(
            SnapshotData snapshot,
            ServerLevel level,
            List<BlockPoint> positions
    ) throws IOException {
        if (snapshot == null || positions == null || positions.isEmpty()) {
            return List.of();
        }

        Map<String, SnapshotChunkData> chunks = new LinkedHashMap<>();
        for (SnapshotChunkData chunk : snapshot.chunks()) {
            chunks.put(chunkKey(chunk.chunkX(), chunk.chunkZ()), chunk);
        }

        Map<ChunkPoint, List<PreparedBlockPlacement>> placementsByChunk = new LinkedHashMap<>();
        BlockStateDecoder blockStateDecoder = this.blockStateDecoderFactory.get();
        Map<CompoundTag, BlockState> stateCache = new LinkedHashMap<>();
        BlockState airState = this.decodeCached(level, AIR_TAG, blockStateDecoder, stateCache);
        for (BlockPoint point : positions) {
            if (point == null) {
                continue;
            }
            ChunkPoint chunkPoint = ChunkPoint.from(point);
            BlockTarget target = this.readBlockTarget(
                    snapshot,
                    chunks.get(chunkKey(chunkPoint.x(), chunkPoint.z())),
                    point,
                    level,
                    blockStateDecoder,
                    airState,
                    stateCache
            );
            placementsByChunk.computeIfAbsent(chunkPoint, ignored -> new ArrayList<>())
                    .add(new PreparedBlockPlacement(point.toBlockPos(), target.state(), target.blockEntityTag()));
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<ChunkPoint, List<PreparedBlockPlacement>> entry : placementsByChunk.entrySet()) {
            batches.add(new PreparedChunkBatch(entry.getKey(), entry.getValue()));
        }
        return batches;
    }

    private PreparedChunkBatch prepareChunk(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            ServerLevel level,
            BlockStateDecoder blockStateDecoder,
            BlockState airState,
            Collection<String> excludedEntityTypes
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

            if (minY == sectionBaseY && maxY == sectionBaseY + 15) {
                PreparedSectionApplyBatch uniformBatch = this.tryPrepareUniformFullSection(
                        chunk,
                        chunkPoint,
                        section,
                        snapshot.minBuildHeight(),
                        sectionY,
                        level,
                        blockStateDecoder,
                        airState
                );
                if (uniformBatch != null) {
                    nativeSections.add(uniformBatch);
                    continue;
                }
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
                                : section.paletteIndexAt((localY << 8) | (localZ << 4) | localX);
                        if (section != null && (stateIndex < 0 || stateIndex >= decodedPalette.length)) {
                            throw new IOException("Snapshot section palette index out of range");
                        }
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
                this.prepareEntitySnapshots(chunk.entitySnapshots(), excludedEntityTypes)
        );
    }

    private PreparedSectionApplyBatch tryPrepareUniformFullSection(
            SnapshotChunkData chunk,
            ChunkPoint chunkPoint,
            SnapshotSectionData section,
            int minBuildHeight,
            int sectionY,
            ServerLevel level,
            BlockStateDecoder blockStateDecoder,
            BlockState airState
    ) throws IOException {
        BlockState state;
        if (section == null) {
            state = airState;
        } else {
            int uniformPaletteIndex = this.uniformPaletteIndex(section);
            if (uniformPaletteIndex < 0 || this.hasBlockEntityInSection(chunk, minBuildHeight, sectionY)) {
                return null;
            }
            state = blockStateDecoder.decode(level, section.palette().get(uniformPaletteIndex));
        }
        LumiSectionBuffer buffer = LumiSectionBuffer.fullSection(sectionY, state);
        return new PreparedSectionApplyBatch(
                chunkPoint,
                sectionY,
                buffer,
                this.sectionApplySafetyClassifier.classify(buffer, true),
                true
        );
    }

    private int uniformPaletteIndex(SnapshotSectionData section) {
        if (section == null
                || section.palette() == null
                || section.palette().isEmpty()) {
            return -1;
        }
        int first = section.paletteIndexAt(0);
        if (first < 0 || first >= section.palette().size()) {
            return -1;
        }
        for (int localIndex = 1; localIndex < SectionChangeMask.ENTRY_COUNT; localIndex++) {
            if (section.paletteIndexAt(localIndex) != first) {
                return -1;
            }
        }
        return first;
    }

    private boolean hasBlockEntityInSection(SnapshotChunkData chunk, int minBuildHeight, int sectionY) {
        if (chunk.blockEntities().isEmpty()) {
            return false;
        }
        for (Integer packedIndex : chunk.blockEntities().keySet()) {
            if (packedIndex == null) {
                continue;
            }
            int relativeY = packedIndex >>> 8;
            if (Math.floorDiv(minBuildHeight + relativeY, 16) == sectionY) {
                return true;
            }
        }
        return false;
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

    private BlockTarget readBlockTarget(
            SnapshotData snapshot,
            SnapshotChunkData chunk,
            BlockPoint point,
            ServerLevel level,
            BlockStateDecoder blockStateDecoder,
            BlockState airState,
            Map<CompoundTag, BlockState> stateCache
    ) throws IOException {
        if (chunk == null || point.y() < snapshot.minBuildHeight() || point.y() > snapshot.maxBuildHeight()) {
            return new BlockTarget(airState, null);
        }
        SnapshotSectionData section = this.sectionAt(chunk, Math.floorDiv(point.y(), 16));
        if (section == null) {
            return new BlockTarget(
                    airState,
                    this.readBlockEntity(chunk, snapshot.minBuildHeight(), point.y(), point.x() & 15, point.z() & 15)
            );
        }
        int localIndex = SectionChangeMask.localIndex(point.x(), point.y(), point.z());
        int paletteIndex = section.paletteIndexAt(localIndex);
        if (paletteIndex < 0 || section.palette() == null || paletteIndex >= section.palette().size()) {
            throw new IOException("Snapshot section palette index out of range");
        }
        return new BlockTarget(
                this.decodeCached(level, section.palette().get(paletteIndex), blockStateDecoder, stateCache),
                this.readBlockEntity(chunk, snapshot.minBuildHeight(), point.y(), point.x() & 15, point.z() & 15)
        );
    }

    private BlockState decodeCached(
            ServerLevel level,
            CompoundTag tag,
            BlockStateDecoder blockStateDecoder,
            Map<CompoundTag, BlockState> stateCache
    ) throws IOException {
        CompoundTag key = tag == null ? new CompoundTag() : tag.copy();
        BlockState cached = stateCache.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState decoded = blockStateDecoder.decode(level, tag);
        stateCache.put(key, decoded);
        return decoded;
    }

    private SnapshotSectionData sectionAt(SnapshotChunkData chunk, int sectionY) {
        for (SnapshotSectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return section;
            }
        }
        return null;
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

    private static String chunkKey(int chunkX, int chunkZ) {
        return chunkX + ":" + chunkZ;
    }

    private EntityBatch prepareEntitySnapshots(
            List<EntityPayload> entitySnapshots,
            Collection<String> excludedEntityTypes
    ) {
        if (entitySnapshots == null || entitySnapshots.isEmpty()) {
            return EntityBatch.replaceEntities(List.of(), excludedEntityTypes);
        }
        return EntityBatch.replaceEntities(
                entitySnapshots.stream()
                        .filter(entity -> !this.excluded(entity, excludedEntityTypes))
                        .map(EntityPayload::copyTag)
                        .toList(),
                excludedEntityTypes
        );
    }

    private boolean excluded(EntityPayload entity, Collection<String> excludedEntityTypes) {
        return entity != null
                && excludedEntityTypes != null
                && excludedEntityTypes.contains(entity.entityType());
    }

    private static CompoundTag createAirTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }

    private record BlockTarget(BlockState state, CompoundTag blockEntityTag) {

        private BlockTarget {
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }
    }
}
