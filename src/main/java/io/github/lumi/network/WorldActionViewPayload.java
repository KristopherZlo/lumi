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
public record WorldActionViewPayload(String dimension, UUID epoch, WorldActionView view, Optional<UUID> completedRequest) implements CustomPacketPayload {
    private static final int MAX_ENTRIES = 16_384;
    public static final Type<WorldActionViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LumiMod.MOD_ID, "world_actions_v2"));
    public static final StreamCodec<FriendlyByteBuf, WorldActionViewPayload> CODEC = CustomPacketPayload.codec(WorldActionViewPayload::write, WorldActionViewPayload::read);

    public WorldActionViewPayload {
        Objects.requireNonNull(epoch);
        Objects.requireNonNull(view);
        Objects.requireNonNull(completedRequest);
        if (dimension == null || dimension.isBlank() || dimension.length() > 256) throw new IllegalArgumentException("Invalid action dimension");
        if ((long) view.undo().size() + view.redo().size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many action entries");
        if (java.util.stream.Stream.concat(view.undo().stream(), view.redo().stream())
                .mapToLong(entry -> entry.axiomEntries().size()).sum() > MAX_ENTRIES)
            throw new IllegalArgumentException("Too many native history links");
    }
    public WorldActionViewPayload(String dimension, UUID epoch, WorldActionView view) {
        this(dimension, epoch, view, Optional.empty());
    }
    private void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(dimension, 256);
        buffer.writeUUID(epoch);
        buffer.writeLong(view.revision());
        write(buffer, view.undo());
        write(buffer, view.redo());
        buffer.writeBoolean(completedRequest.isPresent());
        completedRequest.ifPresent(buffer::writeUUID);
    }
    private static void write(FriendlyByteBuf buffer, List<WorldActionView.Entry> entries) {
        buffer.writeVarInt(entries.size());
        for (var entry : entries) {
            buffer.writeUUID(entry.action());
            buffer.writeEnum(entry.kind());
            buffer.writeBoolean(entry.origin().isPresent());
            entry.origin().ifPresent(origin -> { buffer.writeInt(origin.x()); buffer.writeInt(origin.y()); buffer.writeInt(origin.z()); });
            buffer.writeVarInt(entry.axiomEntries().size());
            entry.axiomEntries().forEach(buffer::writeUUID);
        }
    }
    private static WorldActionViewPayload read(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf(256);
        UUID epoch = buffer.readUUID();
        long revision = buffer.readLong();
        var undo = readEntries(buffer, MAX_ENTRIES, MAX_ENTRIES);
        var redo = readEntries(buffer, MAX_ENTRIES - undo.size(), MAX_ENTRIES - undo.stream().mapToInt(entry -> entry.axiomEntries().size()).sum());
        return new WorldActionViewPayload(dimension, epoch, new WorldActionView(revision, undo, redo),
                buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty());
    }
    private static List<WorldActionView.Entry> readEntries(FriendlyByteBuf buffer, int maximum, int linksRemaining) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid action entry count");
        var entries = new ArrayList<WorldActionView.Entry>(count);
        for (int index = 0; index < count; index++) {
            UUID id = buffer.readUUID();
            var kind = buffer.readEnum(WorldActionView.Kind.class);
            var origin = buffer.readBoolean() ? Optional.of(new BlockPosition(buffer.readInt(), buffer.readInt(), buffer.readInt())) : Optional.<BlockPosition>empty();
            int size = buffer.readVarInt();
            if (size < 0 || size > linksRemaining) throw new IllegalArgumentException("Invalid native link count");
            linksRemaining -= size;
            var links = new ArrayList<UUID>(size);
            for (int link = 0; link < size; link++) links.add(buffer.readUUID());
            entries.add(new WorldActionView.Entry(id, kind, origin, links));
        }
        return List.copyOf(entries);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
