package io.github.luma.minecraft.world;

import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.capture.WorldMutationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Replays verification mismatches through the normal prepared apply pipeline.
 */
final class WorldApplyVerificationRepairer {

    private List<SectionBatch> sections = List.of();
    private int sectionIndex;
    private int placementIndex;

    void start(List<SectionBatch> repairSections) {
        this.sections = repairSections == null ? List.of() : List.copyOf(repairSections);
        this.sectionIndex = 0;
        this.placementIndex = 0;
    }

    boolean hasPending() {
        this.normalizeCursor();
        return this.sectionIndex < this.sections.size();
    }

    int pendingCount() {
        this.normalizeCursor();
        int pending = 0;
        for (int index = this.sectionIndex; index < this.sections.size(); index++) {
            SectionBatch section = this.sections.get(index);
            if (section == null) {
                continue;
            }
            int start = index == this.sectionIndex ? this.placementIndex : 0;
            pending += Math.max(0, section.placementCount() - start);
        }
        return pending;
    }

    void clear() {
        this.sections = List.of();
        this.sectionIndex = 0;
        this.placementIndex = 0;
    }

    int drain(
            ServerLevel level,
            WorldApplyBudget budget,
            long deadlineNanos,
            WorldApplyMetrics metrics,
            RedstoneReplayUpdateQueue redstoneUpdateQueue,
            WorldLightUpdateQueue lightUpdateQueue
    ) {
        if (level == null || !this.hasPending()) {
            return 0;
        }

        return this.drain(level, this.repairBudget(budget), deadlineNanos, metrics,
                redstoneUpdateQueue, lightUpdateQueue);
    }

    private int drain(
            ServerLevel level,
            int maxBlocks,
            long deadlineNanos,
            WorldApplyMetrics metrics,
            RedstoneReplayUpdateQueue redstoneUpdateQueue,
            WorldLightUpdateQueue lightUpdateQueue
    ) {
        if (maxBlocks <= 0) {
            return 0;
        }

        int repaired = 0;
        try (
                WorldMutationContext.SourceFrame ignoredSource =
                        WorldMutationContext.pushSource(WorldMutationSource.RESTORE);
                WorldMutationContext.SuppressionFrame ignoredSuppression =
                        WorldMutationContext.pushCaptureSuppression()
        ) {
            WorldRedstoneReplayUpdateContext.push(redstoneUpdateQueue);
            WorldLightUpdateContext.push(lightUpdateQueue);
            try {
                while (this.hasPending() && repaired < maxBlocks && System.nanoTime() < deadlineNanos) {
                    repaired += this.repairCurrentSection(level, maxBlocks - repaired, metrics);
                }
            } finally {
                WorldLightUpdateContext.pop();
                WorldRedstoneReplayUpdateContext.pop();
            }
        }
        return repaired;
    }

    private int repairCurrentSection(
            ServerLevel level,
            int maxBlocks,
            WorldApplyMetrics metrics
    ) {
        SectionBatch section = this.sections.get(this.sectionIndex);
        int startIndex = this.placementIndex;
        int processed = BlockChangeApplier.applySectionBatch(level, section, startIndex, maxBlocks, metrics);
        if (processed <= 0) {
            this.sectionIndex += 1;
            this.placementIndex = 0;
            return 0;
        }

        List<Map.Entry<BlockPos, CompoundTag>> blockEntities = this.blockEntities(section, startIndex, processed);
        if (!blockEntities.isEmpty()) {
            BlockChangeApplier.applyBlockEntities(level, blockEntities, 0, blockEntities.size(), metrics);
        }
        this.placementIndex += processed;
        if (this.placementIndex >= section.placementCount()) {
            this.sectionIndex += 1;
            this.placementIndex = 0;
        }
        return processed;
    }

    private List<Map.Entry<BlockPos, CompoundTag>> blockEntities(
            SectionBatch section,
            int startIndex,
            int processedPlacements
    ) {
        if (section == null || processedPlacements <= 0) {
            return List.of();
        }
        List<Map.Entry<BlockPos, CompoundTag>> blockEntities = new ArrayList<>();
        int endIndex = Math.min(section.placementCount(), startIndex + processedPlacements);
        for (int index = startIndex; index < endIndex; index++) {
            PreparedBlockPlacement placement = section.placements().get(index);
            if (placement != null && placement.blockEntityTag() != null) {
                blockEntities.add(Map.entry(placement.pos(), placement.blockEntityTag()));
            }
        }
        return blockEntities;
    }

    private int repairBudget(WorldApplyBudget budget) {
        if (budget == null) {
            return 32;
        }
        return Math.max(32, Math.min(budget.sparseStepCap(), budget.maxBlocks()));
    }

    private void normalizeCursor() {
        while (this.sectionIndex < this.sections.size()) {
            SectionBatch section = this.sections.get(this.sectionIndex);
            if (section != null && this.placementIndex < section.placementCount()) {
                return;
            }
            this.sectionIndex += 1;
            this.placementIndex = 0;
        }
    }
}
