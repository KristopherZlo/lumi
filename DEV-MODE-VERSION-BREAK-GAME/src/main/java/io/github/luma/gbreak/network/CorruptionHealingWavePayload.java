package io.github.luma.gbreak.network;

import io.github.luma.gbreak.GBreakDevMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record CorruptionHealingWavePayload(
        BlockPos center,
        int maxRadiusBlocks,
        int blocksPerStep,
        int intervalTicks,
        int startDelayTicks,
        boolean blackoutMode
) implements CustomPayload {

    public static final CustomPayload.Id<CorruptionHealingWavePayload> ID =
            new CustomPayload.Id<>(Identifier.of(GBreakDevMod.MOD_ID, "corruption_healing_wave"));

    public static final PacketCodec<RegistryByteBuf, CorruptionHealingWavePayload> CODEC =
            CustomPayload.codecOf(CorruptionHealingWavePayload::write, CorruptionHealingWavePayload::read);

    public CorruptionHealingWavePayload {
        center = center.toImmutable();
        maxRadiusBlocks = Math.max(1, maxRadiusBlocks);
        blocksPerStep = Math.max(1, blocksPerStep);
        intervalTicks = Math.max(1, intervalTicks);
        startDelayTicks = Math.max(0, startDelayTicks);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void write(CorruptionHealingWavePayload payload, RegistryByteBuf buf) {
        buf.writeBlockPos(payload.center);
        buf.writeVarInt(payload.maxRadiusBlocks);
        buf.writeVarInt(payload.blocksPerStep);
        buf.writeVarInt(payload.intervalTicks);
        buf.writeVarInt(payload.startDelayTicks);
        buf.writeBoolean(payload.blackoutMode);
    }

    private static CorruptionHealingWavePayload read(RegistryByteBuf buf) {
        return new CorruptionHealingWavePayload(
                buf.readBlockPos(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean()
        );
    }
}
