package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
        boolean operationActive,
        UUID workspaceId,
        String workspaceName,
        String branchName,
        List<Version> versions) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_VERSIONS = 32;
    public static final Type<HistorySnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, HistorySnapshotPayload> CODEC =
            CustomPacketPayload.codec(HistorySnapshotPayload::write, HistorySnapshotPayload::read);

    public HistorySnapshotPayload {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceName, "workspaceName");
        Objects.requireNonNull(branchName, "branchName");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (revision < 0 || pendingKeys < 0) {
            throw new IllegalArgumentException("Snapshot counters cannot be negative");
        }
        if (workspaceName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || branchName.isBlank()
                || branchName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || versions.size() > MAX_VERSIONS) {
            throw new IllegalArgumentException("Invalid workspace history snapshot");
        }
    }

    public HistorySnapshotPayload(
            String dimensionId,
            CommitId head,
            long revision,
            int pendingKeys,
            boolean operationActive) {
        this(dimensionId, head, revision, pendingKeys, operationActive,
                new UUID(0, 0), "", "unknown", List.of());
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarInt(pendingKeys);
        buffer.writeBoolean(operationActive);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(workspaceName, MAX_TEXT_BYTES);
        buffer.writeUtf(branchName, MAX_TEXT_BYTES);
        buffer.writeVarInt(versions.size());
        versions.forEach(version -> version.write(buffer));
    }

    private static HistorySnapshotPayload read(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        CommitId head = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        int pending = buffer.readVarInt();
        boolean active = buffer.readBoolean();
        UUID workspace = buffer.readUUID();
        String workspaceName = buffer.readUtf(MAX_TEXT_BYTES);
        String branch = buffer.readUtf(MAX_TEXT_BYTES);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_VERSIONS) {
            throw new IllegalArgumentException("Invalid history version count");
        }
        java.util.ArrayList<Version> versions = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            versions.add(Version.read(buffer));
        }
        return new HistorySnapshotPayload(
                dimension, head, revision, pending, active,
                workspace, workspaceName, branch, versions);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Version(
            CommitId id, String message, String author, long timestampMillis, CommitKind kind) {
        public Version {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(kind, "kind");
            if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                    || author.isBlank()
                    || author.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Invalid history version metadata");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(id.hex(), ObjectId.HEX_LENGTH);
            buffer.writeUtf(message, MAX_TEXT_BYTES);
            buffer.writeUtf(author, MAX_TEXT_BYTES);
            buffer.writeLong(timestampMillis);
            buffer.writeByte(kind.code());
        }

        private static Version read(FriendlyByteBuf buffer) {
            return new Version(
                    new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                    buffer.readUtf(MAX_TEXT_BYTES), buffer.readUtf(MAX_TEXT_BYTES),
                    buffer.readLong(), CommitKind.fromCode(buffer.readUnsignedByte()));
        }
    }
}
