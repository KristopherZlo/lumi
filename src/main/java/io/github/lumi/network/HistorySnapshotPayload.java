package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Immutable current-dimension history state consumed by client controllers. */
public record HistorySnapshotPayload(
        String dimensionId,
        CommitId head,
        long revision,
        int pendingKeys,
        boolean operationActive) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    public static final Type<HistorySnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, HistorySnapshotPayload> CODEC =
            CustomPacketPayload.codec(HistorySnapshotPayload::write, HistorySnapshotPayload::read);

    public HistorySnapshotPayload {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(head, "head");
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (revision < 0 || pendingKeys < 0) {
            throw new IllegalArgumentException("Snapshot counters cannot be negative");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarInt(pendingKeys);
        buffer.writeBoolean(operationActive);
    }

    private static HistorySnapshotPayload read(FriendlyByteBuf buffer) {
        return new HistorySnapshotPayload(
                buffer.readUtf(MAX_DIMENSION_BYTES),
                new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                buffer.readVarLong(), buffer.readVarInt(), buffer.readBoolean());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
