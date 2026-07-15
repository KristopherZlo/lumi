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

    void warmUp() {
        initialize(ClientboundSectionBlocksUpdatePacket.class);
        initialize(ClientboundBlockEntityDataPacket.class);
    }

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
                new ClientboundSectionBlocksUpdatePacket(sectionPos, renderInvalidationCells(changedCells), section);
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

    static ShortSet renderInvalidationCells(ShortSet changedCells) {
        ShortOpenHashSet cells = new ShortOpenHashSet();
        if (changedCells == null || changedCells.isEmpty()) {
            return cells;
        }

        for (short cell : changedCells.toShortArray()) {
            int localX = localX(cell);
            int localY = localY(cell);
            int localZ = localZ(cell);
            cells.add(cell);
            addCellIfInside(cells, localX - 1, localY, localZ);
            addCellIfInside(cells, localX + 1, localY, localZ);
            addCellIfInside(cells, localX, localY - 1, localZ);
            addCellIfInside(cells, localX, localY + 1, localZ);
            addCellIfInside(cells, localX, localY, localZ - 1);
            addCellIfInside(cells, localX, localY, localZ + 1);
        }
        return cells;
    }

    private static void initialize(Class<?> type) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Could not initialize world apply packet type " + type.getName(), exception);
        }
    }

    private static void addCellIfInside(ShortSet cells, int localX, int localY, int localZ) {
        if (localX < 0 || localX > 15 || localY < 0 || localY > 15 || localZ < 0 || localZ > 15) {
            return;
        }
        cells.add(cell(localX, localY, localZ));
    }

    private static short cell(int localX, int localY, int localZ) {
        return (short) ((localX << 8) | (localZ << 4) | localY);
    }

    private static int localX(short cell) {
        return (cell >>> 8) & 15;
    }

    private static int localY(short cell) {
        return cell & 15;
    }

    private static int localZ(short cell) {
        return (cell >>> 4) & 15;
    }
}
