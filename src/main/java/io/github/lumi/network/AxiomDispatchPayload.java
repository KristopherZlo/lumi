package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** FIFO begin/end markers around every packet of one native Axiom dispatch. */
public record AxiomDispatchPayload(UUID dispatch, String dimension, boolean complete) implements CustomPacketPayload {
    public static final Type<AxiomDispatchPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "axiom_dispatch"));
    public static final StreamCodec<FriendlyByteBuf, AxiomDispatchPayload> CODEC =
            CustomPacketPayload.codec(AxiomDispatchPayload::write, AxiomDispatchPayload::read);

    public AxiomDispatchPayload {
        Objects.requireNonNull(dispatch, "dispatch");
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("Invalid dispatch dimension");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(dispatch);
        buffer.writeUtf(dimension, 256);
        buffer.writeBoolean(complete);
    }

    private static AxiomDispatchPayload read(FriendlyByteBuf buffer) {
        return new AxiomDispatchPayload(buffer.readUUID(), buffer.readUtf(256), buffer.readBoolean());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
