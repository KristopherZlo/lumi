package io.github.lumi.network;

import io.github.lumi.LumiMod;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlated bounded result of one cleanup inspection or apply request. */
public record CleanupResultPayload(
        UUID requestId,
        boolean applied,
        int commits,
        int objects,
        String error) implements CustomPacketPayload {
    private static final int MAX_ERROR_CHARS = 4096;
    public static final Type<CleanupResultPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "cleanup_result"));
    public static final StreamCodec<FriendlyByteBuf, CleanupResultPayload> CODEC =
            CustomPacketPayload.codec(CleanupResultPayload::write, CleanupResultPayload::read);

    public CleanupResultPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(error, "error");
        if (commits < 0 || objects < 0 || error.length() > MAX_ERROR_CHARS) {
            throw new IllegalArgumentException("Invalid cleanup result");
        }
    }

    public boolean succeeded() {
        return error.isEmpty();
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeBoolean(applied);
        buffer.writeVarInt(commits);
        buffer.writeVarInt(objects);
        buffer.writeUtf(error, MAX_ERROR_CHARS);
    }

    private static CleanupResultPayload read(FriendlyByteBuf buffer) {
        return new CleanupResultPayload(
                buffer.readUUID(), buffer.readBoolean(),
                buffer.readVarInt(), buffer.readVarInt(),
                buffer.readUtf(MAX_ERROR_CHARS));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
