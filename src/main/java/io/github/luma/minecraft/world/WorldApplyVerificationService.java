package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Incrementally verifies one applied chunk without exceeding the caller's
 * tick deadline.
 */
final class WorldApplyVerificationService {

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final BlockPlacementUpdateDecider updateDecider = new BlockPlacementUpdateDecider();
    private final LongSupplier nanoTime;
    private Verification activeVerification;
    private ChunkBatch activeBatch;

    WorldApplyVerificationService() {
        this(System::nanoTime);
    }

    WorldApplyVerificationService(LongSupplier nanoTime) {
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
    }

    WorldApplyVerificationResult verify(ServerLevel level, ChunkBatch batch) {
        if (level == null) {
            return WorldApplyVerificationResult.empty();
        }
        return this.verify(batch, placement -> this.requiresRepair(level, placement));
    }

    WorldApplyVerificationResult verify(ChunkBatch batch, PlacementRepairDetector repairDetector) {
        Verification verification = this.begin(batch, repairDetector);
        WorldApplyVerificationResult result;
        do {
            result = verification.advance(Long.MAX_VALUE);
        } while (result == null);
        return result;
    }

    WorldApplyVerificationResult advance(ServerLevel level, ChunkBatch batch, long deadlineNanos) {
        if (level == null || batch == null) {
            this.clear();
            return WorldApplyVerificationResult.empty();
        }
        if (this.activeVerification == null || this.activeBatch != batch) {
            this.activeBatch = batch;
            this.activeVerification = this.begin(batch, placement -> this.requiresRepair(level, placement));
        }
        WorldApplyVerificationResult result = this.activeVerification.advance(deadlineNanos);
        if (result != null) {
            this.clear();
        }
        return result;
    }

    Verification begin(ChunkBatch batch, PlacementRepairDetector repairDetector) {
        return new Verification(batch, repairDetector, this.nanoTime);
    }

    void clear() {
        this.activeVerification = null;
        this.activeBatch = null;
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

    static final class Verification {

        private final List<PreparedSectionApplyBatch> nativeSections;
        private final List<SectionBatch> sections;
        private final PlacementRepairDetector repairDetector;
        private final LongSupplier nanoTime;
        private final Map<BlockPos, PreparedBlockPlacement> placements = new LinkedHashMap<>();
        private final Map<Integer, List<PreparedBlockPlacement>> repairsBySection = new LinkedHashMap<>();
        private final Map<Integer, BitSet> changedCellsBySection = new LinkedHashMap<>();
        private BitSet nativeCells;
        private Iterator<PreparedBlockPlacement> indexedPlacements;
        private int nativeSectionIndex;
        private int nativeLocalIndex;
        private int sectionIndex;
        private int placementIndex;
        private int matched;
        private int mismatched;
        private int skipped;

        private Verification(
                ChunkBatch batch,
                PlacementRepairDetector repairDetector,
                LongSupplier nanoTime
        ) {
            this.nativeSections = batch == null ? List.of() : batch.orderedNativeSections();
            this.sections = batch == null ? List.of() : batch.orderedSections();
            this.repairDetector = repairDetector;
            this.nanoTime = nanoTime;
        }

        WorldApplyVerificationResult advance(long deadlineNanos) {
            if (this.repairDetector == null) {
                return WorldApplyVerificationResult.empty();
            }
            while (this.nanoTime.getAsLong() < deadlineNanos) {
                if (this.indexedPlacements == null) {
                    if (this.indexNextPlacement()) {
                        continue;
                    }
                    this.indexedPlacements = this.placements.values().iterator();
                }
                if (this.indexedPlacements.hasNext()) {
                    this.verify(this.indexedPlacements.next());
                    continue;
                }
                return this.result();
            }
            return null;
        }

        private boolean indexNextPlacement() {
            while (this.nativeSectionIndex < this.nativeSections.size()) {
                PreparedSectionApplyBatch section = this.nativeSections.get(this.nativeSectionIndex);
                if (section == null || section.buffer() == null) {
                    this.advanceNativeSection();
                    continue;
                }
                if (this.nativeCells == null) {
                    this.nativeCells = BitSet.valueOf(section.buffer().changedCells().words());
                }
                int localIndex = this.nativeCells.nextSetBit(this.nativeLocalIndex);
                if (localIndex < 0) {
                    this.advanceNativeSection();
                    continue;
                }
                this.nativeLocalIndex = localIndex + 1;
                this.index(this.placement(section, localIndex));
                return true;
            }
            while (this.sectionIndex < this.sections.size()) {
                SectionBatch section = this.sections.get(this.sectionIndex);
                if (section == null || section.placements() == null
                        || this.placementIndex >= section.placementCount()) {
                    this.sectionIndex += 1;
                    this.placementIndex = 0;
                    continue;
                }
                this.index(section.placements().get(this.placementIndex));
                this.placementIndex += 1;
                return true;
            }
            return false;
        }

        private void advanceNativeSection() {
            this.nativeSectionIndex += 1;
            this.nativeLocalIndex = 0;
            this.nativeCells = null;
        }

        private void index(PreparedBlockPlacement placement) {
            if (placement != null && placement.pos() != null) {
                this.placements.put(placement.pos().immutable(), placement);
            }
        }

        private PreparedBlockPlacement placement(PreparedSectionApplyBatch section, int localIndex) {
            return new PreparedBlockPlacement(
                    new BlockPos(
                            (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                            (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                            (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
                    ),
                    section.buffer().targetStateAt(localIndex),
                    section.buffer().blockEntityPlan().tagAt(localIndex),
                    section.buffer().replayHintAt(localIndex)
            );
        }

        private void verify(PreparedBlockPlacement placement) {
            if (placement == null || placement.pos() == null || placement.state() == null) {
                this.skipped += 1;
                return;
            }
            if (!this.repairDetector.requiresRepair(placement)) {
                this.matched += 1;
                return;
            }
            this.mismatched += 1;
            int sectionY = Math.floorDiv(placement.pos().getY(), 16);
            this.repairsBySection.computeIfAbsent(sectionY, ignored -> new ArrayList<>()).add(placement);
            this.changedCellsBySection.computeIfAbsent(
                    sectionY,
                    ignored -> new BitSet(SectionChangeMask.ENTRY_COUNT)
            ).set(SectionChangeMask.localIndex(
                    placement.pos().getX(),
                    placement.pos().getY(),
                    placement.pos().getZ()
            ));
        }

        private WorldApplyVerificationResult result() {
            List<SectionBatch> repairSections = new ArrayList<>(this.repairsBySection.size());
            this.repairsBySection.forEach((sectionY, placements) -> repairSections.add(new SectionBatch(
                    sectionY,
                    this.changedCellsBySection.getOrDefault(
                            sectionY,
                            new BitSet(SectionChangeMask.ENTRY_COUNT)
                    ),
                    placements
            )));
            return new WorldApplyVerificationResult(
                    this.matched,
                    this.mismatched,
                    0,
                    this.skipped,
                    repairSections
            );
        }
    }

    @FunctionalInterface
    interface PlacementRepairDetector {

        boolean requiresRepair(PreparedBlockPlacement placement);
    }
}
