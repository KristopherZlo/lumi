package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class DirectSectionCommitEligibility {

    BlockCommitFallbackReason validate(ServerLevel level, SectionBatch batch, int startIndex, int maxBlocks) {
        if (level == null) {
            return BlockCommitFallbackReason.NULL_LEVEL;
        }
        return this.validateBatch(batch, startIndex, maxBlocks);
    }

    BlockCommitFallbackReason validateBatch(SectionBatch batch, int startIndex, int maxBlocks) {
        if (batch == null || batch.placements().isEmpty() || maxBlocks <= 0 || startIndex >= batch.placements().size()) {
            return BlockCommitFallbackReason.EMPTY_BATCH;
        }

        int endIndex = Math.min(batch.placements().size(), startIndex + maxBlocks);
        PreparedBlockPlacement first = batch.placements().get(startIndex);
        int chunkX = first.pos().getX() >> 4;
        int chunkZ = first.pos().getZ() >> 4;
        for (int index = startIndex; index < endIndex; index++) {
            BlockPos pos = batch.placements().get(index).pos();
            if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) {
                return BlockCommitFallbackReason.MIXED_CHUNK;
            }
            if (Math.floorDiv(pos.getY(), 16) != batch.sectionY()) {
                return BlockCommitFallbackReason.MIXED_SECTION;
            }
        }
        return BlockCommitFallbackReason.NONE;
    }
}
