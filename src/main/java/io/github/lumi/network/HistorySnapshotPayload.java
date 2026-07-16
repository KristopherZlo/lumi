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
        boolean recoveryPending,
        UUID workspaceId,
        String workspaceName,
        String branchName,
        List<Version> versions,
        List<Branch> branches,
        List<ZoneView> zones) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_VERSIONS = 32;
    private static final int MAX_ZONES = 64;
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
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (revision < 0 || pendingKeys < 0) {
            throw new IllegalArgumentException("Snapshot counters cannot be negative");
        }
        if (workspaceName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || branchName.isBlank()
                || branchName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || versions.size() > MAX_VERSIONS
                || branches.size() > 64 || zones.size() > MAX_ZONES) {
            throw new IllegalArgumentException("Invalid workspace history snapshot");
        }
    }

    public HistorySnapshotPayload(
            String dimensionId,
            CommitId head,
            long revision,
            int pendingKeys,
            boolean operationActive,
            boolean recoveryPending,
            UUID workspaceId,
            String workspaceName,
            String branchName,
            List<Version> versions,
            List<Branch> branches) {
        this(dimensionId, head, revision, pendingKeys, operationActive,
                recoveryPending, workspaceId, workspaceName, branchName,
                versions, branches, List.of());
    }

    public HistorySnapshotPayload(
            String dimensionId,
            CommitId head,
            long revision,
            int pendingKeys,
            boolean operationActive) {
        this(dimensionId, head, revision, pendingKeys, operationActive,
                false, new UUID(0, 0), "", "unknown", List.of(), List.of(), List.of());
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarInt(pendingKeys);
        buffer.writeBoolean(operationActive);
        buffer.writeBoolean(recoveryPending);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(workspaceName, MAX_TEXT_BYTES);
        buffer.writeUtf(branchName, MAX_TEXT_BYTES);
        buffer.writeVarInt(versions.size());
        versions.forEach(version -> version.write(buffer));
        buffer.writeVarInt(branches.size());
        branches.forEach(branch -> branch.write(buffer));
        buffer.writeVarInt(zones.size());
        zones.forEach(zone -> zone.write(buffer));
    }

    private static HistorySnapshotPayload read(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        CommitId head = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        int pending = buffer.readVarInt();
        boolean active = buffer.readBoolean();
        boolean recovery = buffer.readBoolean();
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
        int branchCount = buffer.readVarInt();
        if (branchCount < 0 || branchCount > 64) {
            throw new IllegalArgumentException("Invalid branch count");
        }
        java.util.ArrayList<Branch> branches = new java.util.ArrayList<>(branchCount);
        for (int index = 0; index < branchCount; index++) {
            branches.add(Branch.read(buffer));
        }
        int zoneCount = buffer.readVarInt();
        if (zoneCount < 0 || zoneCount > MAX_ZONES) {
            throw new IllegalArgumentException("Invalid zone count");
        }
        java.util.ArrayList<ZoneView> zones = new java.util.ArrayList<>(zoneCount);
        for (int index = 0; index < zoneCount; index++) {
            zones.add(ZoneView.read(buffer));
        }
        return new HistorySnapshotPayload(
                dimension, head, revision, pending, active, recovery,
                workspace, workspaceName, branch, versions, branches, zones);
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

    public record Branch(String name, CommitId head, boolean active) {
        public Branch {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(head, "head");
            if (name.isBlank()
                    || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Invalid branch name");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(name, MAX_TEXT_BYTES);
            buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
            buffer.writeBoolean(active);
        }

        private static Branch read(FriendlyByteBuf buffer) {
            return new Branch(
                    buffer.readUtf(MAX_TEXT_BYTES),
                    new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                    buffer.readBoolean());
        }
    }

    public record ZoneView(
            UUID id, String name, int color, int cells, long revision, boolean active) {
        public ZoneView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()
                    || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_TEXT_BYTES || cells < 0 || revision < 0) {
                throw new IllegalArgumentException("Invalid zone metadata");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(name, MAX_TEXT_BYTES);
            buffer.writeInt(color);
            buffer.writeVarInt(cells);
            buffer.writeVarLong(revision);
            buffer.writeBoolean(active);
        }

        private static ZoneView read(FriendlyByteBuf buffer) {
            return new ZoneView(buffer.readUUID(), buffer.readUtf(MAX_TEXT_BYTES),
                    buffer.readInt(), buffer.readVarInt(), buffer.readVarLong(),
                    buffer.readBoolean());
        }
    }
}
