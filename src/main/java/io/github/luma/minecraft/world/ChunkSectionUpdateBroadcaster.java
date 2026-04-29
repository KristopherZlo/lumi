package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class ChunkSectionUpdateBroadcaster {

    int broadcastSection(
            ServerLevel level,
            SectionPos sectionPos,
            ShortSet changedCells,
            LevelChunkSection section
    ) {
        if (level == null || sectionPos == null || changedCells == null || changedCells.isEmpty() || section == null) {
            return 0;
        }

        ClientboundSectionBlocksUpdatePacket packet =
                new ClientboundSectionBlocksUpdatePacket(sectionPos, changedCells, section);
        int sent = 0;
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(sectionPos.chunk(), false)) {
            player.connection.send(packet);
            sent += 1;
        }
        return sent > 0 ? 1 : 0;
    }

    int broadcastBlockEntity(ServerLevel level, BlockEntity blockEntity) {
        if (level == null || blockEntity == null) {
            return 0;
        }

        ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(blockEntity);
        ChunkPos chunk = new ChunkPos(blockEntity.getBlockPos());
        int sent = 0;
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) {
            player.connection.send(packet);
            sent += 1;
        }
        return sent > 0 ? 1 : 0;
    }

    static ShortSet changedCells(List<BlockPos> positions) {
        ShortOpenHashSet cells = new ShortOpenHashSet();
        if (positions == null || positions.isEmpty()) {
            return cells;
        }

        for (BlockPos pos : positions) {
            cells.add(SectionPos.sectionRelativePos(pos));
        }
        return cells;
    }
}
