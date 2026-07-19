package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BranchName;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlated bounded request for a workspace or zone history page. */
public record HistoryPageRequestPayload(
        UUID requestId,
        String dimensionId,
        UUID workspaceId,
        BranchName branch,
        Optional<UUID> zoneId,
        int offset,
        int limit,
        String query) implements CustomPacketPayload {
    private static final int MAX_TEXT_BYTES = 1024;
    public static final Type<HistoryPageRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "history_page_request"));
    public static final StreamCodec<FriendlyByteBuf, HistoryPageRequestPayload> CODEC =
            CustomPacketPayload.codec(
                    HistoryPageRequestPayload::write,
                    HistoryPageRequestPayload::read);

    public HistoryPageRequestPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(branch, "branch");
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        query = Objects.requireNonNull(query, "query").trim();
        if (dimensionId.isBlank() || offset < 0 || limit < 1 || limit > 64
                || offset > 1_000 - limit
                || query.codePointCount(0, query.length()) > 128
                || query.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid history page request");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_TEXT_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeUtf(branch.value(), MAX_TEXT_BYTES);
        buffer.writeBoolean(zoneId.isPresent());
        zoneId.ifPresent(buffer::writeUUID);
        buffer.writeVarInt(offset);
        buffer.writeVarInt(limit);
        buffer.writeUtf(query, MAX_TEXT_BYTES);
    }

    private static HistoryPageRequestPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_TEXT_BYTES);
        UUID workspace = buffer.readUUID();
        BranchName branch = new BranchName(buffer.readUtf(MAX_TEXT_BYTES));
        Optional<UUID> zone = buffer.readBoolean()
                ? Optional.of(buffer.readUUID()) : Optional.empty();
        return new HistoryPageRequestPayload(
                request, dimension, workspace, branch, zone,
                buffer.readVarInt(), buffer.readVarInt(),
                buffer.readUtf(MAX_TEXT_BYTES));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
