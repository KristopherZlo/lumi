package io.github.luma.gbreak.network;

import io.github.luma.gbreak.GBreakDevMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record CorruptionRestoreFadePayload(List<BlockPos> positions) implements CustomPayload {

    private static final int MAX_POSITIONS = 4096;

    public static final CustomPayload.Id<CorruptionRestoreFadePayload> ID =
            new CustomPayload.Id<>(Identifier.of(GBreakDevMod.MOD_ID, "corruption_restore_fade"));

    public static final PacketCodec<RegistryByteBuf, CorruptionRestoreFadePayload> CODEC =
            CustomPayload.codecOf(CorruptionRestoreFadePayload::write, CorruptionRestoreFadePayload::read);

    public CorruptionRestoreFadePayload {
        positions = List.copyOf(positions);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void write(CorruptionRestoreFadePayload payload, RegistryByteBuf buf) {
        int count = Math.min(payload.positions.size(), MAX_POSITIONS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeBlockPos(payload.positions.get(i));
        }
    }

    private static CorruptionRestoreFadePayload read(RegistryByteBuf buf) {
        int encodedCount = Math.max(0, buf.readVarInt());
        int storedCount = Math.min(encodedCount, MAX_POSITIONS);
        List<BlockPos> positions = new ArrayList<>(storedCount);
        for (int i = 0; i < encodedCount; i++) {
            BlockPos pos = buf.readBlockPos().toImmutable();
            if (i < storedCount) {
                positions.add(pos);
            }
        }
        return new CorruptionRestoreFadePayload(positions);
    }
}
