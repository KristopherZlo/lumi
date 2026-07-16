package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PackageName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded package metadata shown before the client confirms an import. */
public record PackageInspectionPayload(
        UUID requestId,
        String dimensionId,
        PackageName packageName,
        CommitId sourceCommit,
        String message,
        String author,
        long totalBytes,
        int objectCount) implements CustomPacketPayload {
    private static final int MAX_DIMENSION_CHARS = 1024;
    private static final int MAX_TEXT_CHARS = 4096;
    public static final Type<PackageInspectionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "package_inspection"));
    public static final StreamCodec<FriendlyByteBuf, PackageInspectionPayload> CODEC =
            CustomPacketPayload.codec(
                    PackageInspectionPayload::write, PackageInspectionPayload::read);

    public PackageInspectionPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(author, "author");
        if (dimensionId.isBlank() || dimensionId.length() > MAX_DIMENSION_CHARS
                || message.length() > MAX_TEXT_CHARS
                || author.isBlank() || author.length() > MAX_TEXT_CHARS
                || totalBytes < 1 || objectCount < 0) {
            throw new IllegalArgumentException("Invalid Lumi package inspection");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_DIMENSION_CHARS);
        buffer.writeUtf(packageName.value(), 96);
        buffer.writeUtf(sourceCommit.hex(), ObjectId.HEX_LENGTH);
        buffer.writeUtf(message, MAX_TEXT_CHARS);
        buffer.writeUtf(author, MAX_TEXT_CHARS);
        buffer.writeVarLong(totalBytes);
        buffer.writeVarInt(objectCount);
    }

    private static PackageInspectionPayload read(FriendlyByteBuf buffer) {
        return new PackageInspectionPayload(
                buffer.readUUID(),
                buffer.readUtf(MAX_DIMENSION_CHARS),
                new PackageName(buffer.readUtf(96)),
                new CommitId(new ObjectId(buffer.readUtf(ObjectId.HEX_LENGTH))),
                buffer.readUtf(MAX_TEXT_CHARS),
                buffer.readUtf(MAX_TEXT_CHARS),
                buffer.readVarLong(),
                buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
