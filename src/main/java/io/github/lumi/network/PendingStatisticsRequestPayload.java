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

/** Correlated request for exact current-world versus HEAD block totals. */
public record PendingStatisticsRequestPayload(
        UUID requestId,
        String dimensionId,
        UUID workspaceId,
        CommitId head,
        long revision,
        long pendingRevision) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    public static final Type<PendingStatisticsRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    LumiMod.MOD_ID, "pending_statistics_request"));
    public static final StreamCodec<FriendlyByteBuf, PendingStatisticsRequestPayload>
            CODEC = CustomPacketPayload.codec(
                    PendingStatisticsRequestPayload::write,
                    PendingStatisticsRequestPayload::read);

    public PendingStatisticsRequestPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(head, "head");
        if (dimensionId.isBlank()
                || dimensionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length > MAX_DIMENSION_BYTES
                || revision < 0 || pendingRevision < 0) {
            throw new IllegalArgumentException(
                    "Invalid pending statistics request");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarLong(pendingRevision);
    }

    private static PendingStatisticsRequestPayload read(
            FriendlyByteBuf buffer) {
        return new PendingStatisticsRequestPayload(
                buffer.readUUID(),
                buffer.readUtf(MAX_DIMENSION_BYTES),
                buffer.readUUID(),
                new CommitId(new ObjectId(
                        buffer.readUtf(ObjectId.HEX_LENGTH))),
                buffer.readVarLong(),
                buffer.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
