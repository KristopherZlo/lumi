package io.github.luma.minecraft.world;

import net.minecraft.server.level.ServerLevel;

interface BlockCommitStrategy {

    BlockCommitResult apply(ServerLevel level, SectionBatch batch, int startIndex, int maxBlocks);
}
