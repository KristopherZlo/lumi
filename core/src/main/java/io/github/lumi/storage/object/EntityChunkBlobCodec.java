package io.github.lumi.storage.object;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class EntityChunkBlobCodec {
    private static final int MAGIC = 0x4C554532;
    private static final int MAX_ENTITIES = 65_536;
    private static final int MAX_TYPE_BYTES = 1024 * 1024;
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;

    public byte[] encode(EntityChunkBlob chunk) throws IOException {
        if (chunk.entities().size() > MAX_ENTITIES) {
            throw new IOException("Entity chunk exceeds " + MAX_ENTITIES + " entities");
        }
        List<EntityState> entities = chunk.entities().stream()
                .sorted(Comparator.comparing(EntityState::id))
                .toList();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(entities.size());
            for (EntityState entity : entities) {
                output.writeLong(entity.id().getMostSignificantBits());
                output.writeLong(entity.id().getLeastSignificantBits());
                CanonicalBytes.writeString(output, entity.type(), MAX_TYPE_BYTES, "entity type");
                CanonicalBytes.write(output, entity.nbt().bytes(), MAX_NBT_BYTES, "entity NBT");
            }
        }
        return bytes.toByteArray();
    }

    public EntityChunkBlob decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Not a Lumi V2 entity chunk blob");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTITIES) {
                throw new IOException("Invalid entity count");
            }
            List<EntityState> entities = new ArrayList<>(count);
            UUID previous = null;
            for (int index = 0; index < count; index++) {
                UUID id = new UUID(input.readLong(), input.readLong());
                if (previous != null && previous.compareTo(id) >= 0) {
                    throw new IOException("Entities are not in canonical UUID order");
                }
                previous = id;
                String type = CanonicalBytes.readString(input, MAX_TYPE_BYTES, "entity type");
                CanonicalNbt nbt = new CanonicalNbt(CanonicalBytes.read(input, MAX_NBT_BYTES, "entity NBT"));
                entities.add(new EntityState(id, type, nbt));
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in entity chunk blob");
            }
            return new EntityChunkBlob(entities);
        } catch (EOFException truncated) {
            throw new IOException("Truncated entity chunk blob", truncated);
        }
    }
}
