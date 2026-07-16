package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Rewrites a loaded section directly, then repairs derived live-world state once. */
@SuppressWarnings("deprecation")
final class MinecraftSectionRewriter {
    private final ServerLevel level;

    MinecraftSectionRewriter(ServerLevel level) {
        this.level = level;
    }

    void apply(LevelChunk chunk, SectionKey key, DecodedSection target) {
        int sectionIndex = chunk.getSectionIndexFromSectionY(key.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            throw new IllegalArgumentException("Section is outside the loaded chunk: " + key);
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        ShortSet changedCells = new ShortOpenHashSet();
        int[] highestChangedByColumn = new int[16 * 16];
        Arrays.fill(highestChangedByColumn, -1);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

        for (int localIndex = 0; localIndex < target.blockStates().size(); localIndex++) {
            int x = localIndex & 15;
            int z = (localIndex >>> 4) & 15;
            int y = (localIndex >>> 8) & 15;
            BlockState current = section.getBlockState(x, y, z);
            BlockState replacement = target.blockStates().get(localIndex);
            if (current.equals(replacement)) {
                continue;
            }
            section.setBlockState(x, y, z, replacement, false);
            position.set(
                    key.chunkX() * 16 + x,
                    key.sectionY() * 16 + y,
                    key.chunkZ() * 16 + z);
            changedCells.add(SectionPos.sectionRelativePos(position));
            int column = x | (z << 4);
            if (highestChangedByColumn[column] < localIndex) {
                highestChangedByColumn[column] = localIndex;
            }
            if (requiresLightCheck(current, replacement)) {
                level.getLightEngine().checkBlock(position);
            }
        }
        if (changedCells.isEmpty()) {
            return;
        }

        section.recalcBlockCounts();
        updateHeightmaps(chunk, key, section, highestChangedByColumn);
        chunk.markUnsaved();
        broadcast(chunk, key, changedCells, section);
    }

    private static void updateHeightmaps(
            LevelChunk chunk,
            SectionKey key,
            LevelChunkSection section,
            int[] highestChangedByColumn) {
        for (int localIndex : highestChangedByColumn) {
            if (localIndex < 0) {
                continue;
            }
            int x = localIndex & 15;
            int z = (localIndex >>> 4) & 15;
            int y = (localIndex >>> 8) & 15;
            BlockState state = section.getBlockState(x, y, z);
            int worldY = key.sectionY() * 16 + y;
            for (var heightmap : chunk.getHeightmaps()) {
                heightmap.getValue().update(x, worldY, z, state);
            }
        }
    }

    private void broadcast(
            LevelChunk chunk,
            SectionKey key,
            ShortSet changedCells,
            LevelChunkSection section) {
        SectionPos sectionPos = SectionPos.of(chunk.getPos(), key.sectionY());
        var packet = new ClientboundSectionBlocksUpdatePacket(
                sectionPos, changedCells, section);
        for (var player : level.getChunkSource().chunkMap
                .getPlayers(chunk.getPos(), false)) {
            player.connection.send(packet);
        }
    }

    private static boolean requiresLightCheck(BlockState current, BlockState target) {
        return current.getLightEmission() != target.getLightEmission()
                || current.getLightBlock() != target.getLightBlock()
                || current.useShapeForLightOcclusion() != target.useShapeForLightOcclusion()
                || current.propagatesSkylightDown() != target.propagatesSkylightDown()
                || current.canOcclude() != target.canOcclude()
                || current.blocksMotion() != target.blocksMotion()
                || !current.getFluidState().equals(target.getFluidState());
    }
}
