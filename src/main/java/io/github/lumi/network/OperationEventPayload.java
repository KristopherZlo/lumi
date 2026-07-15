package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Immutable accepted or terminal operation event for one client request. */
public record OperationEventPayload(
        UUID requestId,
        String dimensionId,
        State state,
        String message,
        CommitId head,
        long revision) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_MESSAGE_BYTES = 4096;
    public static final Type<OperationEventPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "operation_event"));
    public static final StreamCodec<FriendlyByteBuf, OperationEventPayload> CODEC =
            CustomPacketPayload.codec(OperationEventPayload::write, OperationEventPayload::read);

    public OperationEventPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(head, "head");
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Operation message is too large");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Ref revision cannot be negative");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeByte(state.code);
        buffer.writeUtf(message, MAX_MESSAGE_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
    }

    private static OperationEventPayload read(FriendlyByteBuf buffer) {
        return new OperationEventPayload(
                buffer.readUUID(), buffer.readUtf(MAX_DIMENSION_BYTES),
                State.fromCode(buffer.readUnsignedByte()), buffer.readUtf(MAX_MESSAGE_BYTES),
                new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                buffer.readVarLong());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum State {
        ACCEPTED(0), SUCCEEDED(1), FAILED(2), CANCELLED(3), RETURNED(4), DEGRADED(5);
        private final int code;
        State(int code) { this.code = code; }
        private static State fromCode(int code) {
            for (State value : values()) {
                if (value.code == code) return value;
            }
            throw new IllegalArgumentException("Unknown operation event: " + code);
        }
    }
}
