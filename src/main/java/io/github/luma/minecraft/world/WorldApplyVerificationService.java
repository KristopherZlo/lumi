package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class WorldApplyVerificationService {

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final BlockPlacementUpdateDecider updateDecider = new BlockPlacementUpdateDecider();

    WorldApplyVerificationResult verify(ServerLevel level, ChunkBatch batch) {
        if (level == null) {
            return WorldApplyVerificationResult.empty();
        }
        return this.verify(batch, placement -> this.requiresRepair(level, placement));
    }

    WorldApplyVerificationResult verify(ChunkBatch batch, PlacementRepairDetector repairDetector) {
        if (batch == null || repairDetector == null) {
            return WorldApplyVerificationResult.empty();
        }
        int matched = 0;
        int mismatched = 0;
        int skipped = 0;
        Map<Integer, List<PreparedBlockPlacement>> repairsBySection = new LinkedHashMap<>();
        Map<Integer, BitSet> changedCellsBySection = new LinkedHashMap<>();

        for (PreparedBlockPlacement placement : this.placements(batch)) {
            if (placement == null || placement.pos() == null || placement.state() == null) {
                skipped += 1;
                continue;
            }
            if (repairDetector.requiresRepair(placement)) {
                mismatched += 1;
                int sectionY = Math.floorDiv(placement.pos().getY(), 16);
                repairsBySection.computeIfAbsent(sectionY, ignored -> new ArrayList<>()).add(placement);
                changedCellsBySection.computeIfAbsent(sectionY, ignored -> new BitSet(SectionChangeMask.ENTRY_COUNT))
                        .set(SectionChangeMask.localIndex(
                                placement.pos().getX(),
                                placement.pos().getY(),
                                placement.pos().getZ()
                        ));
            } else {
                matched += 1;
            }
        }

        List<SectionBatch> repairSections = new ArrayList<>();
        repairsBySection.forEach((sectionY, placements) -> repairSections.add(new SectionBatch(
                sectionY,
                changedCellsBySection.getOrDefault(sectionY, new BitSet(SectionChangeMask.ENTRY_COUNT)),
                placements
        )));
        return new WorldApplyVerificationResult(matched, mismatched, 0, skipped, repairSections);
    }

    private boolean requiresRepair(ServerLevel level, PreparedBlockPlacement placement) {
        PersistentBlockStatePolicy.PersistentBlockState target = this.blockStatePolicy.normalize(
                placement.state(),
                placement.blockEntityTag()
        );
        return this.updateDecider.requiresUpdate(
                level,
                placement.pos(),
                level.getBlockState(placement.pos()),
                target.state(),
                target.blockEntityTag()
        );
    }

    private List<PreparedBlockPlacement> placements(ChunkBatch batch) {
        Map<BlockPos, PreparedBlockPlacement> byPosition = new LinkedHashMap<>();
        for (PreparedSectionApplyBatch nativeSection : batch.orderedNativeSections()) {
            for (PreparedBlockPlacement placement : nativeSection.toPlacements()) {
                if (placement != null && placement.pos() != null) {
                    byPosition.put(placement.pos().immutable(), placement);
                }
            }
        }
        for (SectionBatch section : batch.orderedSections()) {
            for (PreparedBlockPlacement placement : section.placements()) {
                if (placement != null && placement.pos() != null) {
                    byPosition.put(placement.pos().immutable(), placement);
                }
            }
        }
        return List.copyOf(byPosition.values());
    }

    @FunctionalInterface
    interface PlacementRepairDetector {

        boolean requiresRepair(PreparedBlockPlacement placement);
    }
}
