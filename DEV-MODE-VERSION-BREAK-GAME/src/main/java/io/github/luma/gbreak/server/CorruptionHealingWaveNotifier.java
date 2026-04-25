package io.github.luma.gbreak.server;

import io.github.luma.gbreak.network.CorruptionHealingWavePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

final class CorruptionHealingWaveNotifier {

    void notifyStarted(
            ServerWorld world,
            BlockPos center,
            int maxRadiusBlocks,
            int blocksPerStep,
            int intervalTicks
    ) {
        CorruptionHealingWavePayload payload = new CorruptionHealingWavePayload(
                center,
                maxRadiusBlocks,
                blocksPerStep,
                intervalTicks
        );
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (ServerPlayNetworking.canSend(player, CorruptionHealingWavePayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
