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

/** Ref-CAS guarded history intent sent to the authoritative server. */
public record HistoryCommandPayload(
        UUID requestId,
        Kind kind,
        String argument,
        CommitId expectedCommit,
        long expectedRevision) implements CustomPacketPayload {
    private static final int MAX_ARGUMENT_BYTES = 4096;
    public static final Type<HistoryCommandPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_command"));
    public static final StreamCodec<FriendlyByteBuf, HistoryCommandPayload> CODEC =
            CustomPacketPayload.codec(HistoryCommandPayload::write, HistoryCommandPayload::read);

    public HistoryCommandPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(argument, "argument");
        Objects.requireNonNull(expectedCommit, "expectedCommit");
        if (argument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            throw new IllegalArgumentException("Command argument is too large");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected ref revision cannot be negative");
        }
        if (kind == Kind.RESTORE || kind == Kind.RESTORE_NO_ENTITIES) {
            new ObjectId(argument);
        }
        if (kind == Kind.RESTORE_AREA) {
            PartialRestoreArgument.parse(argument);
        }
        if ((kind == Kind.QUICK_ROLLBACK || kind == Kind.UNDO || kind == Kind.REDO
                || kind == Kind.RECOVER_RESUME || kind == Kind.RECOVER_RETURN)
                && !argument.isEmpty()) {
            throw new IllegalArgumentException(kind + " does not accept an argument");
        }
        if ((kind == Kind.BRANCH_SWITCH || kind == Kind.BRANCH_CREATE)
                && argument.isBlank()) {
            throw new IllegalArgumentException(kind + " requires a branch name");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeByte(kind.code);
        buffer.writeUtf(argument, MAX_ARGUMENT_BYTES);
        buffer.writeUtf(expectedCommit.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(expectedRevision);
    }

    private static HistoryCommandPayload read(FriendlyByteBuf buffer) {
        return new HistoryCommandPayload(
                buffer.readUUID(), Kind.fromCode(buffer.readUnsignedByte()),
                buffer.readUtf(MAX_ARGUMENT_BYTES),
                new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                buffer.readVarLong());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum Kind {
        SAVE(0), RESTORE(1), QUICK_ROLLBACK(2), UNDO(3), REDO(4), BRANCH_SWITCH(5),
        RECOVER_RESUME(6), RECOVER_RETURN(7), BRANCH_CREATE(8),
        RESTORE_NO_ENTITIES(9), RESTORE_AREA(10);
        private final int code;
        Kind(int code) { this.code = code; }
        private static Kind fromCode(int code) {
            for (Kind value : values()) {
                if (value.code == code) return value;
            }
            throw new IllegalArgumentException("Unknown history command: " + code);
        }
    }
}
