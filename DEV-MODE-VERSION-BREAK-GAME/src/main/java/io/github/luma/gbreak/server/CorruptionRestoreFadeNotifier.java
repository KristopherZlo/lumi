package io.github.luma.gbreak.server;

import io.github.luma.gbreak.network.CorruptionRestoreFadePayload;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

final class CorruptionRestoreFadeNotifier {

    void notifyRestoredBlocks(ServerWorld world, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }

        CorruptionRestoreFadePayload payload = new CorruptionRestoreFadePayload(positions);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (ServerPlayNetworking.canSend(player, CorruptionRestoreFadePayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
