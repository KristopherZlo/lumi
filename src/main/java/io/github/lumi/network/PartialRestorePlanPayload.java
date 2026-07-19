package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlated exact result for one clean-state partial-Restore preview. */
public record PartialRestorePlanPayload(
        UUID requestId,
        UUID previewToken,
        String dimensionId,
        CommitId target,
        BlockAreaTarget area,
        int changedSections,
        long changedBlocks,
        String error) implements CustomPacketPayload {
    private static final int MAX_TEXT_CHARS = 1024;
    public static final Type<PartialRestorePlanPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "partial_restore_plan"));
    public static final StreamCodec<FriendlyByteBuf, PartialRestorePlanPayload> CODEC =
            CustomPacketPayload.codec(
                    PartialRestorePlanPayload::write,
                    PartialRestorePlanPayload::read);

    public PartialRestorePlanPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(previewToken, "previewToken");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank() || dimensionId.length() > MAX_TEXT_CHARS
                || error.length() > MAX_TEXT_CHARS
                || changedSections < 0 || changedBlocks < 0) {
            throw new IllegalArgumentException("Invalid partial Restore plan result");
        }
    }

    public boolean succeeded() {
        return error.isEmpty();
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUUID(previewToken);
        buffer.writeUtf(dimensionId, MAX_TEXT_CHARS);
        buffer.writeUtf(target.hex(), ObjectId.HEX_LENGTH);
        BlockBox box = area.area();
        buffer.writeInt(box.minX());
        buffer.writeInt(box.minY());
        buffer.writeInt(box.minZ());
        buffer.writeInt(box.maxX());
        buffer.writeInt(box.maxY());
        buffer.writeInt(box.maxZ());
        buffer.writeBoolean(area.outside());
        buffer.writeVarInt(changedSections);
        buffer.writeVarLong(changedBlocks);
        buffer.writeUtf(error, MAX_TEXT_CHARS);
    }

    private static PartialRestorePlanPayload read(FriendlyByteBuf buffer) {
        UUID requestId = buffer.readUUID();
        UUID previewToken = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_TEXT_CHARS);
        CommitId target = new CommitId(
                new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH)));
        BlockAreaTarget area = new BlockAreaTarget(new BlockBox(
                buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readInt(), buffer.readInt(), buffer.readInt()),
                buffer.readBoolean());
        return new PartialRestorePlanPayload(
                requestId, previewToken, dimension, target, area,
                buffer.readVarInt(), buffer.readVarLong(),
                buffer.readUtf(MAX_TEXT_CHARS));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
