package io.github.luma.gbreak.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class GBreakNetwork {

    private static boolean registered;

    private GBreakNetwork() {
    }

    public static void registerPayloads() {
        if (registered) {
            return;
        }

        PayloadTypeRegistry.playS2C().register(CorruptionRestoreFadePayload.ID, CorruptionRestoreFadePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CorruptionHealingWavePayload.ID, CorruptionHealingWavePayload.CODEC);
        registered = true;
    }
}
