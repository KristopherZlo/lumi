package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchName;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlated immutable history page prepared away from the server tick. */
public record HistoryPagePayload(
        UUID requestId,
        String dimensionId,
        UUID workspaceId,
        BranchName branch,
        Optional<UUID> zoneId,
        int offset,
        boolean hasMore,
        List<HistorySnapshotPayload.Version> versions,
        List<BranchName> branches,
        String error) implements CustomPacketPayload {
    private static final int MAX_TEXT_BYTES = 1024;
    public static final int MAX_VERSIONS = 64;
    public static final Type<HistoryPagePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_page"));
    public static final StreamCodec<FriendlyByteBuf, HistoryPagePayload> CODEC =
            CustomPacketPayload.codec(
                    HistoryPagePayload::write, HistoryPagePayload::read);

    public HistoryPagePayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(branch, "branch");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank() || offset < 0
                || versions.size() > MAX_VERSIONS
                || branches.size() > 64
                || (!error.isEmpty()
                        && (!versions.isEmpty() || !branches.isEmpty() || hasMore))) {
            throw new IllegalArgumentException("Invalid history page");
        }
    }

    public HistoryPagePayload(
            UUID requestId,
            String dimensionId,
            UUID workspaceId,
            BranchName branch,
            Optional<UUID> zoneId,
            int offset,
            boolean hasMore,
            List<HistorySnapshotPayload.Version> versions,
            String error) {
        this(requestId, dimensionId, workspaceId, branch, zoneId,
                offset, hasMore, versions, List.of(), error);
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_TEXT_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(branch.value(), MAX_TEXT_BYTES);
        buffer.writeBoolean(zoneId.isPresent());
        zoneId.ifPresent(buffer::writeUUID);
        buffer.writeVarInt(offset);
        buffer.writeBoolean(hasMore);
        buffer.writeVarInt(versions.size());
        versions.forEach(version -> version.write(buffer));
        buffer.writeVarInt(branches.size());
        branches.forEach(value -> buffer.writeUtf(value.value(), MAX_TEXT_BYTES));
        buffer.writeUtf(error, MAX_TEXT_BYTES);
    }

    private static HistoryPagePayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_TEXT_BYTES);
        UUID workspace = buffer.readUUID();
        BranchName branch = new BranchName(buffer.readUtf(MAX_TEXT_BYTES));
        Optional<UUID> zone = buffer.readBoolean()
                ? Optional.of(buffer.readUUID()) : Optional.empty();
        int offset = buffer.readVarInt();
        boolean more = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_VERSIONS) {
            throw new IllegalArgumentException("Invalid history page size");
        }
        List<HistorySnapshotPayload.Version> versions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            versions.add(HistorySnapshotPayload.Version.read(buffer));
        }
        int branchCount = buffer.readVarInt();
        if (branchCount < 0 || branchCount > 64) {
            throw new IllegalArgumentException("Invalid history branch count");
        }
        List<BranchName> branches = new ArrayList<>(branchCount);
        for (int index = 0; index < branchCount; index++) {
            branches.add(new BranchName(buffer.readUtf(MAX_TEXT_BYTES)));
        }
        return new HistoryPagePayload(
                request, dimension, workspace, branch, zone, offset, more,
                versions, branches, buffer.readUtf(MAX_TEXT_BYTES));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
