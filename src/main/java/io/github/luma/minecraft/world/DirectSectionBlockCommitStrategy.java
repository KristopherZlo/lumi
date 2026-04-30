package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

final class DirectSectionBlockCommitStrategy implements BlockCommitStrategy {

    private final PersistentBlockStatePolicy blockStatePolicy;
    private final BlockPlacementUpdateDecider updateDecider;
    private final ChunkSectionUpdateBroadcaster updateBroadcaster;
    private final DirectSectionCommitEligibility eligibility = new DirectSectionCommitEligibility();
    private final SectionLightUpdatePlanner lightUpdatePlanner = new SectionLightUpdatePlanner();

    DirectSectionBlockCommitStrategy(
            PersistentBlockStatePolicy blockStatePolicy,
            BlockPlacementUpdateDecider updateDecider,
            ChunkSectionUpdateBroadcaster updateBroadcaster
    ) {
        this.blockStatePolicy = blockStatePolicy;
        this.updateDecider = updateDecider;
        this.updateBroadcaster = updateBroadcaster;
    }

    @Override
    public BlockCommitResult apply(ServerLevel level, SectionBatch batch, int startIndex, int maxBlocks) {
        BlockCommitFallbackReason reason = this.eligibility.validate(level, batch, startIndex, maxBlocks);
        if (reason != BlockCommitFallbackReason.NONE) {
            return new VanillaBlockCommitStrategy(reason).apply(level, batch, startIndex, maxBlocks);
        }

        PreparedBlockPlacement first = batch.placements().get(startIndex);
        int chunkX = first.pos().getX() >> 4;
        int chunkZ = first.pos().getZ() >> 4;
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.CHUNK_NOT_LOADED)
                    .apply(level, batch, startIndex, maxBlocks);
        }

        int sectionIndex = chunk.getSectionIndexFromSectionY(batch.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.SECTION_OUT_OF_RANGE)
                    .apply(level, batch, startIndex, maxBlocks);
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.SECTION_MISSING)
                    .apply(level, batch, startIndex, maxBlocks);
        }

        int endIndex = Math.min(batch.placements().size(), startIndex + maxBlocks);
        List<BlockPos> changedPositions = new ArrayList<>();
        int skipped = 0;
        int lightChecks = 0;
        for (int index = startIndex; index < endIndex; index++) {
            PreparedBlockPlacement placement = batch.placements().get(index);
            PersistentBlockStatePolicy.PersistentBlockState persistentState =
                    this.blockStatePolicy.normalize(placement.state(), placement.blockEntityTag());
            BlockPos pos = placement.pos();
            BlockState currentState = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            BlockState targetState = persistentState.state();
            CompoundTag targetBlockEntityTag = persistentState.blockEntityTag();
            if (!this.updateDecider.requiresUpdate(level, pos, currentState, targetState, targetBlockEntityTag)) {
                skipped += 1;
                continue;
            }

            level.removeBlockEntity(pos);
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, targetState, false);
            this.updateHeightmaps(chunk, pos, targetState);
            level.updatePOIOnBlockStateChange(pos, currentState, targetState);
            if (this.lightUpdatePlanner.check(level, pos, currentState, targetState)) {
                lightChecks += 1;
            }
            changedPositions.add(pos.immutable());
        }

        if (!changedPositions.isEmpty()) {
            chunk.markUnsaved();
        }

        ShortSet changedCells = ChunkSectionUpdateBroadcaster.changedCells(changedPositions);
        int packets = this.updateBroadcaster.broadcastSection(
                level,
                SectionPos.of(chunk.getPos(), batch.sectionY()),
                changedCells,
                section
        );
        return BlockCommitResult.direct(
                endIndex - startIndex,
                changedPositions.size(),
                skipped,
                packets,
                lightChecks
        );
    }

    private void updateHeightmaps(LevelChunk chunk, BlockPos pos, BlockState state) {
        for (var entry : chunk.getHeightmaps()) {
            Heightmap heightmap = entry.getValue();
            heightmap.update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
        }
    }
}
