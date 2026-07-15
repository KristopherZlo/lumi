package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityChunkBlobCodecTest {
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final EntityChunkBlobCodec codec = new EntityChunkBlobCodec();

    @Test
    void roundTripsEntitiesInCanonicalUuidOrder() throws IOException {
        EntityState first = entity(FIRST_ID, "minecraft:armor_stand", 1);
        EntityState second = entity(SECOND_ID, "minecraft:item_frame", 2);

        byte[] forward = codec.encode(new EntityChunkBlob(List.of(first, second)));
        byte[] reverse = codec.encode(new EntityChunkBlob(List.of(second, first)));

        assertArrayEquals(forward, reverse);
        assertEquals(new EntityChunkBlob(List.of(first, second)), codec.decode(forward));
    }

    @Test
    void refusesDuplicateEntityIdentity() {
        EntityState entity = entity(FIRST_ID, "minecraft:armor_stand", 1);

        assertThrows(IllegalArgumentException.class, () -> new EntityChunkBlob(List.of(entity, entity)));
    }

    @Test
    void rejectsTrailingData() throws IOException {
        byte[] valid = codec.encode(new EntityChunkBlob(List.of()));

        assertThrows(IOException.class,
                () -> codec.decode(java.util.Arrays.copyOf(valid, valid.length + 1)));
    }

    private static EntityState entity(UUID id, String type, int nbtByte) {
        return new EntityState(id, type, new CanonicalNbt(new byte[] {(byte) nbtByte}));
    }
}
