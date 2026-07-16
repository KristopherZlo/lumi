package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.minecraft.operation.OperationProgress;
import java.util.Objects;
import java.util.Optional;
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
        long revision,
        Optional<UUID> ticketId,
        int queuePosition,
        Optional<OperationProgress> progress) implements CustomPacketPayload {
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
        ticketId = Objects.requireNonNull(ticketId, "ticketId");
        progress = Objects.requireNonNull(progress, "progress");
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Operation message is too large");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Ref revision cannot be negative");
        }
        if (ticketId.isPresent() != (queuePosition >= 0)) {
            throw new IllegalArgumentException("Queue position requires an operation ticket");
        }
        if ((state == State.PROGRESS) != progress.isPresent()) {
            throw new IllegalArgumentException("Only progress events carry progress");
        }
    }

    public OperationEventPayload(
            UUID requestId,
            String dimensionId,
            State state,
            String message,
            CommitId head,
            long revision) {
        this(requestId, dimensionId, state, message, head, revision,
                Optional.empty(), -1, Optional.empty());
    }

    public OperationEventPayload(
            UUID requestId,
            String dimensionId,
            State state,
            String message,
            CommitId head,
            long revision,
            Optional<UUID> ticketId,
            int queuePosition) {
        this(requestId, dimensionId, state, message, head, revision,
                ticketId, queuePosition, Optional.empty());
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeByte(state.code);
        buffer.writeUtf(message, MAX_MESSAGE_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeBoolean(ticketId.isPresent());
        ticketId.ifPresent(buffer::writeUUID);
        buffer.writeVarInt(queuePosition + 1);
        buffer.writeBoolean(progress.isPresent());
        progress.ifPresent(value -> {
            buffer.writeUtf(value.phase(), 256);
            buffer.writeVarLong(value.completed());
            buffer.writeVarLong(value.total());
        });
    }

    private static OperationEventPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        State state = State.fromCode(buffer.readUnsignedByte());
        String message = buffer.readUtf(MAX_MESSAGE_BYTES);
        CommitId head = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        Optional<UUID> ticket = buffer.readBoolean()
                ? Optional.of(buffer.readUUID()) : Optional.empty();
        int queuePosition = buffer.readVarInt() - 1;
        Optional<OperationProgress> progress = buffer.readBoolean()
                ? Optional.of(new OperationProgress(
                        buffer.readUtf(256), buffer.readVarLong(), buffer.readVarLong()))
                : Optional.empty();
        return new OperationEventPayload(
                request, dimension, state, message, head, revision,
                ticket, queuePosition, progress);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum State {
        ACCEPTED(0), SUCCEEDED(1), FAILED(2), CANCELLED(3), RETURNED(4), DEGRADED(5),
        PROGRESS(6);
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
