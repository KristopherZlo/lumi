package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Requests cancellation of one owned operation before it becomes active. */
public record OperationCancelPayload(UUID requestId, UUID ticketId)
        implements CustomPacketPayload {
    public static final Type<OperationCancelPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "operation_cancel"));
    public static final StreamCodec<FriendlyByteBuf, OperationCancelPayload> CODEC =
            CustomPacketPayload.codec(OperationCancelPayload::write, OperationCancelPayload::read);

    public OperationCancelPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(ticketId, "ticketId");
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUUID(ticketId);
    }

    private static OperationCancelPayload read(FriendlyByteBuf buffer) {
        return new OperationCancelPayload(buffer.readUUID(), buffer.readUUID());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
