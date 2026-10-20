package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Admission and completion of the original client-side Axiom history executor. */
public record AxiomNativeReplayPayload(UUID request, String dimension, State state) implements CustomPacketPayload {
    public static final Type<AxiomNativeReplayPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "axiom_native_replay"));
    public static final StreamCodec<FriendlyByteBuf, AxiomNativeReplayPayload> CODEC =
            CustomPacketPayload.codec(AxiomNativeReplayPayload::write, AxiomNativeReplayPayload::read);

    public AxiomNativeReplayPayload {
        java.util.Objects.requireNonNull(request);
        java.util.Objects.requireNonNull(state);
        if (dimension == null || dimension.isBlank() || dimension.length() > 256)
            throw new IllegalArgumentException("Invalid native replay dimension");
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(request); buffer.writeUtf(dimension, 256); buffer.writeEnum(state);
    }
    private static AxiomNativeReplayPayload read(FriendlyByteBuf buffer) {
        return new AxiomNativeReplayPayload(buffer.readUUID(), buffer.readUtf(256), buffer.readEnum(State.class));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public enum State { READY, SENT, FAILED }
}
