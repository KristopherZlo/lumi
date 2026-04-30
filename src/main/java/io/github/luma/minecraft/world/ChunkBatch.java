package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Chunk-oriented unit of main-thread application.
 *
 * <p>All block placements for a chunk are grouped by section, block entities
 * are applied in a separate pass, and future entity diffs share the same
 * chunk-scoped commit pipeline.
 */
public record ChunkBatch(
        ChunkPoint chunk,
        Map<Integer, PreparedSectionApplyBatch> nativeSections,
        Map<Integer, SectionBatch> sections,
        Map<BlockPos, CompoundTag> blockEntities,
        EntityBatch entityBatch,
        BatchState state
) {

    public ChunkBatch(
            ChunkPoint chunk,
            Map<Integer, SectionBatch> sections,
            Map<BlockPos, CompoundTag> blockEntities,
            EntityBatch entityBatch,
            BatchState state
    ) {
        this(chunk, Map.of(), sections, blockEntities, entityBatch, state);
    }

    public static ChunkBatch fromPrepared(PreparedChunkBatch batch) {
        Map<Integer, PreparedSectionApplyBatch> nativeSections = new LinkedHashMap<>();
        Map<Integer, List<PreparedBlockPlacement>> placementsBySection = new LinkedHashMap<>();
        Map<Integer, BitSet> changedMasks = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();

        for (PreparedSectionApplyBatch nativeSection : batch.nativeSections()) {
            nativeSections.put(nativeSection.sectionY(), nativeSection);
        }
        for (PreparedBlockPlacement placement : batch.placements()) {
            int sectionY = Math.floorDiv(placement.pos().getY(), 16);
            placementsBySection.computeIfAbsent(sectionY, ignored -> new ArrayList<>()).add(placement);
            changedMasks.computeIfAbsent(sectionY, ignored -> new BitSet(4096))
                    .set(localIndex(placement.pos()));
            if (placement.blockEntityTag() != null) {
                blockEntities.put(placement.pos().immutable(), placement.blockEntityTag().copy());
            }
        }

        Map<Integer, SectionBatch> sections = new LinkedHashMap<>();
        placementsBySection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sections.put(
                        entry.getKey(),
                        new SectionBatch(
                                entry.getKey(),
                                changedMasks.getOrDefault(entry.getKey(), new BitSet(4096)),
                                ConnectedBlockPlacementExpander.ordered(entry.getValue())
                        )
                ));

        return new ChunkBatch(
                batch.chunk(),
                Map.copyOf(nativeSections),
                Map.copyOf(sections),
                Map.copyOf(blockEntities),
                batch.entityBatch(),
                BatchState.COMPLETE
        );
    }

    public List<SectionBatch> orderedSections() {
        return new ArrayList<>(this.sections.values());
    }

    public List<PreparedSectionApplyBatch> orderedNativeSections() {
        return new ArrayList<>(this.nativeSections.values());
    }

    public int totalPlacements() {
        int total = 0;
        for (PreparedSectionApplyBatch section : this.nativeSections.values()) {
            total += section.changedCellCount();
        }
        for (SectionBatch section : this.sections.values()) {
            total += section.placementCount();
        }
        return total;
    }

    public int totalWorkUnits() {
        return this.totalPlacements()
                + this.blockEntities.size()
                + BlockChangeApplier.entityOperationCount(this.entityBatch);
    }

    private static int localIndex(BlockPos pos) {
        return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
    }
}
