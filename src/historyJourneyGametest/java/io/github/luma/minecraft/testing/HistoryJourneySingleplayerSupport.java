package io.github.luma.minecraft.testing;

import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
final class HistoryJourneySingleplayerSupport {

    private static final int CHUNK_SIZE = 16;
    private static final int PLATFORM_MARGIN = 1;
    private static final int PLATFORM_SIZE = 3;
    private static final int PLATFORM_HEADROOM = 24;

    private HistoryJourneySingleplayerSupport() {
    }

    static void prepare(TestSingleplayerContext singleplayer) throws Exception {
        singleplayer.getServer().runOnServer(server -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                throw new IllegalStateException("No singleplayer test player is available");
            }
            ServerPlayer player = players.getFirst();
            server.getPlayerList().op(
                    new NameAndId(player.getGameProfile()),
                    Optional.of(LevelBasedPermissionSet.GAMEMASTER),
                    Optional.of(false)
            );
            stabilize(server.overworld(), player);
        });
        singleplayer.getClientWorld().waitForChunksRender();
    }

    private static void stabilize(ServerLevel level, ServerPlayer player) {
        BlockPos current = player.blockPosition();
        int chunkBaseX = Math.floorDiv(current.getX(), CHUNK_SIZE) * CHUNK_SIZE;
        int chunkBaseZ = Math.floorDiv(current.getZ(), CHUNK_SIZE) * CHUNK_SIZE;
        int platformY = stablePlatformY(level);

        for (int x = chunkBaseX + PLATFORM_MARGIN; x < chunkBaseX + PLATFORM_MARGIN + PLATFORM_SIZE; x++) {
            for (int z = chunkBaseZ + PLATFORM_MARGIN; z < chunkBaseZ + PLATFORM_MARGIN + PLATFORM_SIZE; z++) {
                BlockPos floor = new BlockPos(x, platformY, z);
                level.setBlock(floor, Blocks.BARRIER.defaultBlockState(), 3);
                level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
            }
        }

        double anchorX = chunkBaseX + PLATFORM_MARGIN + 1.5D;
        double anchorZ = chunkBaseZ + PLATFORM_MARGIN + 1.5D;
        double anchorY = platformY + 1.0D;
        player.teleportTo(anchorX, anchorY, anchorZ);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.snapTo(anchorX, anchorY, anchorZ, 180.0F, 20.0F);
    }

    private static int stablePlatformY(ServerLevel level) {
        int minimum = level.getMinY() + 8;
        int maximum = level.getMaxY() - PLATFORM_HEADROOM;
        int preferred = level.getSeaLevel() + 16;
        if (maximum < minimum) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, preferred));
    }
}
