package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        List<PendingBlock> pendingBlocks,
        Optional<BlockBox> pendingBounds,
        boolean operationActive,
        boolean recoveryPending,
        UUID workspaceId,
        String workspaceName,
        String branchName,
        List<WorkspaceView> workspaces,
        List<Version> versions,
        List<Branch> branches,
        List<ZoneView> zones,
        List<Version> deletedVersions) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_VERSIONS = 32;
    private static final int MAX_PENDING_BLOCKS = 512;
    private static final int MAX_WORKSPACES = 64;
    private static final int MAX_ZONES = 64;
    private static final int MAX_ZONE_VERSIONS = 8;
    private static final int MAX_DELETED_VERSIONS = 64;
    public static final Type<HistorySnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, HistorySnapshotPayload> CODEC =
            CustomPacketPayload.codec(HistorySnapshotPayload::write, HistorySnapshotPayload::read);

    public HistorySnapshotPayload {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(head, "head");
        pendingBlocks = List.copyOf(
                Objects.requireNonNull(pendingBlocks, "pendingBlocks"));
        pendingBounds = Objects.requireNonNull(pendingBounds, "pendingBounds");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceName, "workspaceName");
        Objects.requireNonNull(branchName, "branchName");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
        deletedVersions = List.copyOf(
                Objects.requireNonNull(deletedVersions, "deletedVersions"));
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Invalid dimension ID");
        }
        if (revision < 0 || pendingKeys < 0
                || pendingBlocks.size() > MAX_PENDING_BLOCKS) {
            throw new IllegalArgumentException("Snapshot counters cannot be negative");
        }
        if (workspaceName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || branchName.isBlank()
                || branchName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_TEXT_BYTES || workspaces.size() > MAX_WORKSPACES
                || versions.size() > MAX_VERSIONS
                || branches.size() > 64 || zones.size() > MAX_ZONES
                || deletedVersions.size() > MAX_DELETED_VERSIONS) {
            throw new IllegalArgumentException("Invalid workspace history snapshot");
        }
    }

    public HistorySnapshotPayload(
            String dimensionId,
            CommitId head,
            long revision,
            int pendingKeys,
            List<PendingBlock> pendingBlocks,
            boolean operationActive,
            boolean recoveryPending,
            UUID workspaceId,
            String workspaceName,
            String branchName,
            List<WorkspaceView> workspaces,
            List<Version> versions,
            List<Branch> branches,
            List<ZoneView> zones,
            List<Version> deletedVersions) {
        this(dimensionId, head, revision, pendingKeys, pendingBlocks, Optional.empty(),
                operationActive, recoveryPending, workspaceId, workspaceName, branchName,
                workspaces, versions, branches, zones, deletedVersions);
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
            List<WorkspaceView> workspaces,
            List<Version> versions,
            List<Branch> branches,
            List<ZoneView> zones,
            List<Version> deletedVersions) {
        this(dimensionId, head, revision, pendingKeys, List.of(), operationActive,
                recoveryPending, workspaceId, workspaceName, branchName, workspaces,
                versions, branches, zones, deletedVersions);
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
            List<Branch> branches,
            List<ZoneView> zones,
            List<Version> deletedVersions) {
        this(dimensionId, head, revision, pendingKeys, List.of(), operationActive,
                recoveryPending, workspaceId, workspaceName, branchName, List.of(),
                versions, branches, zones, deletedVersions);
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
            List<Branch> branches,
            List<ZoneView> zones) {
        this(dimensionId, head, revision, pendingKeys, List.of(), operationActive,
                recoveryPending, workspaceId, workspaceName, branchName, List.of(),
                versions, branches, zones, List.of());
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
        this(dimensionId, head, revision, pendingKeys, List.of(), operationActive,
                recoveryPending, workspaceId, workspaceName, branchName, List.of(),
                versions, branches, List.of(), List.of());
    }

    public HistorySnapshotPayload(
            String dimensionId,
            CommitId head,
            long revision,
            int pendingKeys,
            boolean operationActive) {
        this(dimensionId, head, revision, pendingKeys, List.of(), operationActive,
                false, new UUID(0, 0), "", "unknown", List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarInt(pendingKeys);
        buffer.writeVarInt(pendingBlocks.size());
        pendingBlocks.forEach(block -> block.write(buffer));
        buffer.writeBoolean(pendingBounds.isPresent());
        pendingBounds.ifPresent(bounds -> {
            buffer.writeInt(bounds.minX());
            buffer.writeInt(bounds.minY());
            buffer.writeInt(bounds.minZ());
            buffer.writeInt(bounds.maxX());
            buffer.writeInt(bounds.maxY());
            buffer.writeInt(bounds.maxZ());
        });
        buffer.writeBoolean(operationActive);
        buffer.writeBoolean(recoveryPending);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(workspaceName, MAX_TEXT_BYTES);
        buffer.writeUtf(branchName, MAX_TEXT_BYTES);
        buffer.writeVarInt(workspaces.size());
        workspaces.forEach(workspace -> workspace.write(buffer));
        buffer.writeVarInt(versions.size());
        versions.forEach(version -> version.write(buffer));
        buffer.writeVarInt(branches.size());
        branches.forEach(branch -> branch.write(buffer));
        buffer.writeVarInt(zones.size());
        zones.forEach(zone -> zone.write(buffer));
        buffer.writeVarInt(deletedVersions.size());
        deletedVersions.forEach(version -> version.write(buffer));
    }

    private static HistorySnapshotPayload read(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        CommitId head = new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        int pending = buffer.readVarInt();
        int pendingBlockCount = buffer.readVarInt();
        if (pendingBlockCount < 0 || pendingBlockCount > MAX_PENDING_BLOCKS) {
            throw new IllegalArgumentException("Invalid pending block count");
        }
        java.util.ArrayList<PendingBlock> pendingBlocks =
                new java.util.ArrayList<>(pendingBlockCount);
        for (int index = 0; index < pendingBlockCount; index++) {
            pendingBlocks.add(PendingBlock.read(buffer));
        }
        Optional<BlockBox> pendingBounds = buffer.readBoolean()
                ? Optional.of(new BlockBox(
                        buffer.readInt(), buffer.readInt(), buffer.readInt(),
                        buffer.readInt(), buffer.readInt(), buffer.readInt()))
                : Optional.empty();
        boolean active = buffer.readBoolean();
        boolean recovery = buffer.readBoolean();
        UUID workspace = buffer.readUUID();
        String workspaceName = buffer.readUtf(MAX_TEXT_BYTES);
        String branch = buffer.readUtf(MAX_TEXT_BYTES);
        int workspaceCount = buffer.readVarInt();
        if (workspaceCount < 0 || workspaceCount > MAX_WORKSPACES) {
            throw new IllegalArgumentException("Invalid workspace count");
        }
        java.util.ArrayList<WorkspaceView> workspaces =
                new java.util.ArrayList<>(workspaceCount);
        for (int index = 0; index < workspaceCount; index++) {
            workspaces.add(WorkspaceView.read(buffer));
        }
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
        int deletedCount = buffer.readVarInt();
        if (deletedCount < 0 || deletedCount > MAX_DELETED_VERSIONS) {
            throw new IllegalArgumentException("Invalid deleted version count");
        }
        java.util.ArrayList<Version> deleted = new java.util.ArrayList<>(deletedCount);
        for (int index = 0; index < deletedCount; index++) {
            deleted.add(Version.read(buffer));
        }
        return new HistorySnapshotPayload(
                dimension, head, revision, pending, pendingBlocks, pendingBounds,
                active, recovery, workspace, workspaceName, branch, workspaces,
                versions, branches, zones, deleted);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record PendingBlock(int x, int y, int z) {
        private void write(FriendlyByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
        }

        private static PendingBlock read(FriendlyByteBuf buffer) {
            return new PendingBlock(
                    buffer.readInt(), buffer.readInt(), buffer.readInt());
        }
    }

    public record WorkspaceView(
            UUID id,
            String name,
            boolean active,
            boolean bounded,
            boolean hideZoneCommits,
            boolean includeEntitiesOnRestore,
            boolean previewGenerationEnabled,
            boolean workspaceHudEnabled) {
        public WorkspaceView(
                UUID id,
                String name,
                boolean active,
                boolean bounded,
                boolean hideZoneCommits,
                boolean includeEntitiesOnRestore) {
            this(id, name, active, bounded, hideZoneCommits,
                    includeEntitiesOnRestore, true, true);
        }

        public WorkspaceView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()
                    || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Invalid workspace metadata");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(name, MAX_TEXT_BYTES);
            buffer.writeBoolean(active);
            buffer.writeBoolean(bounded);
            buffer.writeBoolean(hideZoneCommits);
            buffer.writeBoolean(includeEntitiesOnRestore);
            buffer.writeBoolean(previewGenerationEnabled);
            buffer.writeBoolean(workspaceHudEnabled);
        }

        private static WorkspaceView read(FriendlyByteBuf buffer) {
            return new WorkspaceView(
                    buffer.readUUID(), buffer.readUtf(MAX_TEXT_BYTES),
                    buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean());
        }
    }

    public record Version(
            CommitId id,
            String message,
            String author,
            long timestampMillis,
            CommitKind kind,
            VersionTags tags) {
        public Version(
                CommitId id,
                String message,
                String author,
                long timestampMillis,
                CommitKind kind) {
            this(id, message, author, timestampMillis, kind, VersionTags.empty());
        }

        public Version {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(tags, "tags");
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
            buffer.writeVarInt(tags.values().size());
            tags.values().forEach(tag ->
                    buffer.writeUtf(tag, VersionTags.MAX_TAG_LENGTH * 2));
        }

        private static Version read(FriendlyByteBuf buffer) {
            CommitId id = new CommitId(
                    new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
            String message = buffer.readUtf(MAX_TEXT_BYTES);
            String author = buffer.readUtf(MAX_TEXT_BYTES);
            long timestamp = buffer.readLong();
            CommitKind kind = CommitKind.fromCode(buffer.readUnsignedByte());
            int count = buffer.readVarInt();
            if (count < 0 || count > VersionTags.MAX_TAGS) {
                throw new IllegalArgumentException("Invalid history version tag count");
            }
            java.util.ArrayList<String> tags = new java.util.ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                tags.add(buffer.readUtf(VersionTags.MAX_TAG_LENGTH * 2));
            }
            return new Version(
                    id, message, author, timestamp, kind, new VersionTags(tags));
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
            UUID id,
            String name,
            int color,
            int cells,
            long revision,
            boolean active,
            List<Version> versions) {
        public ZoneView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
            if (name.isBlank()
                    || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_TEXT_BYTES || cells < 0 || revision < 0
                    || versions.size() > MAX_ZONE_VERSIONS
                    || versions.stream().anyMatch(version -> version.kind() != CommitKind.ZONE)) {
                throw new IllegalArgumentException("Invalid zone metadata");
            }
        }

        public ZoneView(
                UUID id, String name, int color, int cells, long revision, boolean active) {
            this(id, name, color, cells, revision, active, List.of());
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(name, MAX_TEXT_BYTES);
            buffer.writeInt(color);
            buffer.writeVarInt(cells);
            buffer.writeVarLong(revision);
            buffer.writeBoolean(active);
            buffer.writeVarInt(versions.size());
            versions.forEach(version -> version.write(buffer));
        }

        private static ZoneView read(FriendlyByteBuf buffer) {
            UUID id = buffer.readUUID();
            String name = buffer.readUtf(MAX_TEXT_BYTES);
            int color = buffer.readInt();
            int cells = buffer.readVarInt();
            long revision = buffer.readVarLong();
            boolean active = buffer.readBoolean();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_ZONE_VERSIONS) {
                throw new IllegalArgumentException("Invalid zone version count");
            }
            java.util.ArrayList<Version> versions = new java.util.ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                versions.add(Version.read(buffer));
            }
            return new ZoneView(id, name, color, cells, revision, active, versions);
        }
    }
}
