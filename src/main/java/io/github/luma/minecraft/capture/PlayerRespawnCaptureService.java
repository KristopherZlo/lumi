package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.PlayerRespawnPoint;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;

public final class PlayerRespawnCaptureService {

    private final ServerThreadExecutor serverThreadExecutor = new ServerThreadExecutor();

    public List<PlayerRespawnPoint> capture(ServerLevel level) throws IOException {
        if (level == null || level.getServer() == null) {
            return List.of();
        }
        return this.serverThreadExecutor.call(level.getServer(), () -> level.getServer()
                .getPlayerList()
                .getPlayers()
                .stream()
                .map(this::playerRespawn)
                .flatMap(Optional::stream)
                .toList());
    }

    private Optional<PlayerRespawnPoint> playerRespawn(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        if (config == null || config.respawnData() == null || LevelData.RespawnData.DEFAULT.equals(config.respawnData())) {
            return Optional.empty();
        }
        BlockPos pos = config.respawnData().pos();
        return Optional.of(new PlayerRespawnPoint(
                player.getUUID().toString(),
                player.getName().getString(),
                config.respawnData().dimension().identifier().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                config.respawnData().yaw(),
                config.respawnData().pitch(),
                config.forced()
        ));
    }
}
