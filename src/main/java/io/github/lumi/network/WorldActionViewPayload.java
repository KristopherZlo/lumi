package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.WorldActionView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server cursor shown by editor history controls; replay data remains server-owned. */
public record WorldActionViewPayload(String dimension, UUID epoch, WorldActionView view) implements CustomPacketPayload {
    private static final int MAX_ENTRIES = 16_384;
    public static final Type<WorldActionViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "world_actions_v1"));
    public static final StreamCodec<FriendlyByteBuf, WorldActionViewPayload> CODEC = CustomPacketPayload.codec(WorldActionViewPayload::write, WorldActionViewPayload::read);

    public WorldActionViewPayload {
        Objects.requireNonNull(epoch);
        Objects.requireNonNull(view);
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) throw new IllegalArgumentException("Invalid action dimension");
        if ((long) view.undo().size() + view.redo().size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many action entries");
    }
    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimension, 256);
        buffer.writeUUID(epoch);
        buffer.writeLong(view.revision());
        write(buffer, view.undo());
        write(buffer, view.redo());
    }
    private static void write(FriendlyByteBuf buffer, List<WorldActionView.Entry> entries) {
        buffer.writeVarInt(entries.size());
        for (var entry : entries) {
            buffer.writeUUID(entry.action());
            buffer.writeEnum(entry.kind());
            buffer.writeBoolean(entry.origin().isPresent());
            entry.origin().ifPresent(origin -> { buffer.writeInt(origin.x()); buffer.writeInt(origin.y()); buffer.writeInt(origin.z()); });
        }
    }
    private static WorldActionViewPayload read(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(256);
        UUID epoch = buffer.readUUID();
        long revision = buffer.readLong();
        var undo = readEntries(buffer, MAX_ENTRIES);
        var redo = readEntries(buffer, MAX_ENTRIES - undo.size());
        return new WorldActionViewPayload(dimension, epoch, new WorldActionView(revision, undo, redo));
    }
    private static List<WorldActionView.Entry> readEntries(FriendlyByteBuf buffer, int maximum) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid action entry count");
        var entries = new ArrayList<WorldActionView.Entry>(count);
        for (int index = 0; index < count; index++) entries.add(new WorldActionView.Entry(buffer.readUUID(), buffer.readEnum(WorldActionView.Kind.class),
                buffer.readBoolean() ? Optional.of(new BlockPosition(buffer.readInt(), buffer.readInt(), buffer.readInt())) : Optional.empty()));
        return List.copyOf(entries);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
