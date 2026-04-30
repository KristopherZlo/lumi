package io.github.luma.minecraft.world;

import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class SectionRewritePreflight {

    private final PersistentBlockStatePolicy blockStatePolicy;

    SectionRewritePreflight(PersistentBlockStatePolicy blockStatePolicy) {
        this.blockStatePolicy = blockStatePolicy;
    }

    BlockCommitFallbackReason rejectionReason(LevelChunkSection section, PreparedSectionApplyBatch batch) {
        BlockCommitFallbackReason targetReason = this.targetRejectionReason(batch.buffer());
        if (targetReason != BlockCommitFallbackReason.NONE) {
            return targetReason;
        }
        return this.currentRejectionReason(section, batch.buffer());
    }

    BlockCommitFallbackReason targetRejectionReason(LumiSectionBuffer buffer) {
        if (buffer == null) {
            return BlockCommitFallbackReason.NONE;
        }
        if (buffer.hasBlockEntities()) {
            return BlockCommitFallbackReason.REWRITE_BLOCK_ENTITY;
        }
        final BlockCommitFallbackReason[] reason = new BlockCommitFallbackReason[] {BlockCommitFallbackReason.NONE};
        buffer.changedCells().forEachSetCell(localIndex -> {
            if (reason[0] != BlockCommitFallbackReason.NONE) {
                return;
            }
            BlockState state = this.blockStatePolicy.normalize(buffer.targetStateAt(localIndex), null).state();
            if (state.hasBlockEntity()) {
                reason[0] = BlockCommitFallbackReason.REWRITE_BLOCK_ENTITY;
            } else if (PoiTypes.hasPoi(state)) {
                reason[0] = BlockCommitFallbackReason.REWRITE_POI;
            }
        });
        return reason[0];
    }

    private BlockCommitFallbackReason currentRejectionReason(LevelChunkSection section, LumiSectionBuffer buffer) {
        if (section == null || buffer == null) {
            return BlockCommitFallbackReason.NONE;
        }
        final BlockCommitFallbackReason[] reason = new BlockCommitFallbackReason[] {BlockCommitFallbackReason.NONE};
        buffer.changedCells().forEachSetCell(localIndex -> {
            if (reason[0] != BlockCommitFallbackReason.NONE) {
                return;
            }
            BlockState state = section.getBlockState(
                    SectionChangeMask.localX(localIndex),
                    SectionChangeMask.localY(localIndex),
                    SectionChangeMask.localZ(localIndex)
            );
            if (state.hasBlockEntity()) {
                reason[0] = BlockCommitFallbackReason.REWRITE_BLOCK_ENTITY;
            } else if (PoiTypes.hasPoi(state)) {
                reason[0] = BlockCommitFallbackReason.REWRITE_POI;
            }
        });
        return reason[0];
    }
}
