package io.github.luma.minecraft.world;

import net.minecraft.server.level.ServerLevel;

final class VanillaBlockCommitStrategy implements BlockCommitStrategy {

    private final BlockCommitFallbackReason reason;

    VanillaBlockCommitStrategy() {
        this(BlockCommitFallbackReason.NONE);
    }

    VanillaBlockCommitStrategy(BlockCommitFallbackReason reason) {
        this.reason = reason == null ? BlockCommitFallbackReason.NONE : reason;
    }

    @Override
    public BlockCommitResult apply(ServerLevel level, SectionBatch batch, int startIndex, int maxBlocks) {
        if (level == null || batch == null || batch.placements().isEmpty()
                || maxBlocks <= 0 || startIndex >= batch.placements().size()) {
            return BlockCommitResult.fallback(0, 0, 0, this.reason);
        }

        int endIndex = Math.min(batch.placements().size(), startIndex + maxBlocks);
        int changed = 0;
        int skipped = 0;
        for (int index = startIndex; index < endIndex; index++) {
            PreparedBlockPlacement placement = batch.placements().get(index);
            if (BlockChangeApplier.applyBlockStateOnlyAndReport(
                    level,
                    placement.pos(),
                    placement.state(),
                    placement.blockEntityTag()
            )) {
                changed += 1;
            } else {
                skipped += 1;
            }
        }
        return BlockCommitResult.fallback(endIndex - startIndex, changed, skipped, this.reason);
    }
}
