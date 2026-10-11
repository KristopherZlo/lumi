package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PendingChangeStatistics;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlated exact workspace and zone block totals for one stable generation. */
public record PendingStatisticsPayload(
        UUID requestId,
        String dimensionId,
        UUID workspaceId,
        CommitId head,
        long revision,
        long pendingRevision,
        PendingChangeStatistics workspace,
        Map<UUID, PendingChangeStatistics> zones,
        Set<UUID> zonesWithPendingEntities,
        String error) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_BYTES = 256;
    private static final int MAX_ERROR_BYTES = 1024;
    private static final int MAX_ZONES = 64;
    public static final Type<PendingStatisticsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    LumiMod.MOD_ID, "pending_statistics"));
    public static final StreamCodec<FriendlyByteBuf, PendingStatisticsPayload>
            CODEC = CustomPacketPayload.codec(
                    PendingStatisticsPayload::write,
                    PendingStatisticsPayload::read);

    public PendingStatisticsPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(workspace, "workspace");
        zones = Map.copyOf(Objects.requireNonNull(zones, "zones"));
        zonesWithPendingEntities = Set.copyOf(Objects.requireNonNull(zonesWithPendingEntities, "zonesWithPendingEntities"));
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank()
                || dimensionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length > MAX_DIMENSION_BYTES
                || revision < 0 || pendingRevision < 0
                || zones.size() > MAX_ZONES
                || !zones.keySet().containsAll(zonesWithPendingEntities)
                || error.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length > MAX_ERROR_BYTES
                || !error.isEmpty()
                        && (!workspace.equals(PendingChangeStatistics.NONE)
                                || !zones.isEmpty())) {
            throw new IllegalArgumentException(
                    "Invalid pending statistics result");
        }
    }

    public PendingStatisticsPayload(UUID requestId, String dimensionId, UUID workspaceId,
            CommitId head, long revision, long pendingRevision, PendingChangeStatistics workspace,
            Map<UUID, PendingChangeStatistics> zones, String error) {
        this(requestId, dimensionId, workspaceId, head, revision, pendingRevision, workspace, zones, Set.of(), error);
    }

    public static PendingStatisticsPayload failure(
            PendingStatisticsRequestPayload request, String error) {
        return new PendingStatisticsPayload(
                request.requestId(), request.dimensionId(),
                request.workspaceId(), request.head(), request.revision(),
                request.pendingRevision(), PendingChangeStatistics.NONE, Map.of(),
                Objects.requireNonNull(error, "error"));
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(head.hex(), ObjectId.HEX_LENGTH);
        buffer.writeVarLong(revision);
        buffer.writeVarLong(pendingRevision);
        writeStatistics(buffer, workspace);
        buffer.writeVarInt(zones.size());
        zones.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    buffer.writeUUID(entry.getKey());
                    writeStatistics(buffer, entry.getValue());
                });
        buffer.writeUtf(error, MAX_ERROR_BYTES);
        buffer.writeVarInt(zonesWithPendingEntities.size());
        zonesWithPendingEntities.stream().sorted().forEach(buffer::writeUUID);
    }

    private static PendingStatisticsPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_DIMENSION_BYTES);
        UUID workspaceId = buffer.readUUID();
        CommitId head = new CommitId(new ObjectId(
                buffer.readUtf(ObjectId.HEX_LENGTH)));
        long revision = buffer.readVarLong();
        long pendingRevision = buffer.readVarLong();
        PendingChangeStatistics workspace = readStatistics(buffer);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ZONES) {
            throw new IllegalArgumentException(
                    "Invalid pending statistics zone count");
        }
        Map<UUID, PendingChangeStatistics> zones = new HashMap<>();
        for (int index = 0; index < count; index++) {
            if (zones.put(buffer.readUUID(), readStatistics(buffer)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate pending statistics zone");
            }
        }
        String error = buffer.readUtf(MAX_ERROR_BYTES);
        int entityZones = buffer.readVarInt();
        if (entityZones < 0 || entityZones > count) throw new IllegalArgumentException("Invalid entity scope count");
        var withEntities = new HashSet<UUID>();
        for (int index = 0; index < entityZones; index++) {
            if (!withEntities.add(buffer.readUUID())) throw new IllegalArgumentException("Duplicate entity scope");
        }
        return new PendingStatisticsPayload(
                request, dimension, workspaceId, head, revision, pendingRevision,
                workspace, zones, withEntities, error);
    }

    private static void writeStatistics(
            FriendlyByteBuf buffer, PendingChangeStatistics statistics) {
        buffer.writeVarLong(statistics.added());
        buffer.writeVarLong(statistics.removed());
        buffer.writeVarLong(statistics.changed());
    }

    private static PendingChangeStatistics readStatistics(
            FriendlyByteBuf buffer) {
        return new PendingChangeStatistics(
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
