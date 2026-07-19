package io.github.lumi.network;

import io.github.lumi.LumiMod;
import io.github.lumi.domain.model.ZoneShellFace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One ordered bounded batch of merged zone shell faces. */
public record ZoneOverlayPayload(
        UUID requestId,
        String dimensionId,
        UUID workspaceId,
        int batchIndex,
        boolean complete,
        Optional<ZoneBatch> zone,
        String error) implements CustomPacketPayload {
    private static final int MAX_TEXT_BYTES = 1024;
    public static final int MAX_FACES = 2_048;
    public static final Type<ZoneOverlayPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    LumiMod.MOD_ID, "zone_overlay"));
    public static final StreamCodec<FriendlyByteBuf, ZoneOverlayPayload> CODEC =
            CustomPacketPayload.codec(
                    ZoneOverlayPayload::write, ZoneOverlayPayload::read);

    public ZoneOverlayPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        zone = Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(error, "error");
        if (dimensionId.isBlank() || batchIndex < 0
                || (!complete && !error.isEmpty())) {
            throw new IllegalArgumentException("Invalid zone overlay batch");
        }
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUtf(dimensionId, MAX_TEXT_BYTES);
        buffer.writeUUID(workspaceId);
        buffer.writeVarInt(batchIndex);
        buffer.writeBoolean(complete);
        buffer.writeBoolean(zone.isPresent());
        zone.ifPresent(value -> value.write(buffer));
        buffer.writeUtf(error, MAX_TEXT_BYTES);
    }

    private static ZoneOverlayPayload read(FriendlyByteBuf buffer) {
        UUID request = buffer.readUUID();
        String dimension = buffer.readUtf(MAX_TEXT_BYTES);
        UUID workspace = buffer.readUUID();
        int batch = buffer.readVarInt();
        boolean complete = buffer.readBoolean();
        Optional<ZoneBatch> zone = buffer.readBoolean()
                ? Optional.of(ZoneBatch.read(buffer)) : Optional.empty();
        return new ZoneOverlayPayload(
                request, dimension, workspace, batch, complete,
                zone, buffer.readUtf(MAX_TEXT_BYTES));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record ZoneBatch(
            UUID id,
            String name,
            int color,
            long revision,
            boolean active,
            boolean entered,
            List<ZoneShellFace> faces) {
        public ZoneBatch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
            if (name.isBlank() || revision < 0 || faces.size() > MAX_FACES) {
                throw new IllegalArgumentException(
                        "Invalid zone overlay entry");
            }
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(name, MAX_TEXT_BYTES);
            buffer.writeInt(color);
            buffer.writeVarLong(revision);
            buffer.writeBoolean(active);
            buffer.writeBoolean(entered);
            buffer.writeVarInt(faces.size());
            faces.forEach(face -> {
                buffer.writeByte(face.side().ordinal());
                buffer.writeInt(face.plane());
                buffer.writeInt(face.minA());
                buffer.writeInt(face.maxA());
                buffer.writeInt(face.minB());
                buffer.writeInt(face.maxB());
            });
        }

        private static ZoneBatch read(FriendlyByteBuf buffer) {
            UUID id = buffer.readUUID();
            String name = buffer.readUtf(MAX_TEXT_BYTES);
            int color = buffer.readInt();
            long revision = buffer.readVarLong();
            boolean active = buffer.readBoolean();
            boolean entered = buffer.readBoolean();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_FACES) {
                throw new IllegalArgumentException(
                        "Invalid zone overlay face count");
            }
            List<ZoneShellFace> faces = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int side = buffer.readUnsignedByte();
                if (side >= ZoneShellFace.Side.values().length) {
                    throw new IllegalArgumentException(
                            "Invalid zone overlay face side");
                }
                faces.add(new ZoneShellFace(
                        ZoneShellFace.Side.values()[side],
                        buffer.readInt(), buffer.readInt(), buffer.readInt(),
                        buffer.readInt(), buffer.readInt()));
            }
            return new ZoneBatch(
                    id, name, color, revision, active, entered, faces);
        }
    }
}
