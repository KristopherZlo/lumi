package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded preview coordinates with explicit zone and history context. */
public record ZonePendingPreviewPayload(
        UUID requestId, String dimensionId, UUID workspaceId, CommitId head,
        long revision, UUID zoneId, long zoneRevision, long pendingRevision,
        List<HistorySnapshotPayload.PendingBlock> blocks, String error) implements CustomPacketPayload {
    public static final int MAX_BLOCKS = 512;
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_ERROR_BYTES = 4096;
    public static final Type<ZonePendingPreviewPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "zone_pending_preview"));
    public static final StreamCodec<FriendlyByteBuf, ZonePendingPreviewPayload> CODEC =
            CustomPacketPayload.codec(ZonePendingPreviewPayload::write, ZonePendingPreviewPayload::read);

    public ZonePendingPreviewPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(zoneId, "zoneId");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank() || dimensionId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_DIMENSION_BYTES || revision < 0 || zoneRevision < 0 || pendingRevision < 0
                || blocks.size() > MAX_BLOCKS || (!error.isEmpty() && !blocks.isEmpty())
                || error.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ERROR_BYTES) {
            throw new IllegalArgumentException("Invalid zone pending preview");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeUUID(zoneId);
        buffer.writeVarLong(zoneRevision);
        buffer.writeVarLong(pendingRevision);
        buffer.writeVarInt(blocks.size());
        for (var block : blocks) {
            buffer.writeInt(block.x());
            buffer.writeInt(block.y());
            buffer.writeInt(block.z());
        }
        buffer.writeUtf(error, MAX_ERROR_BYTES);
    }

    private static ZonePendingPreviewPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        UUID workspace = buffer.readUUID();
        CommitId head = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        UUID zone = buffer.readUUID();
        long zoneRevision = buffer.readVarLong();
        long pendingRevision = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_BLOCKS) throw new IllegalArgumentException("Invalid preview size");
        var blocks = new ArrayList<HistorySnapshotPayload.PendingBlock>(count);
        for (int index = 0; index < count; index++) {
            blocks.add(new HistorySnapshotPayload.PendingBlock(
                    buffer.readInt(), buffer.readInt(), buffer.readInt()));
        }
        return new ZonePendingPreviewPayload(request, dimension, workspace, head, revision,
                zone, zoneRevision, pendingRevision, blocks, buffer.readUtf(MAX_ERROR_BYTES));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
