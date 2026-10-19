package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockSnapshot;
import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionRecord.BlockDelta;
import io.github.lumi.domain.model.WorldActionRecord.EntityDelta;
import io.github.lumi.domain.model.WorldDataVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldActionPayloadCodecTest {
    @Test
    void roundTripsOrderedWorldEndpointsAndRejectsInvalidInputsBeforeAllocation() throws IOException {
        var version = new WorldDataVersion(5000);
        var codec = new WorldActionPayloadCodec();
        var before = new BlockSnapshot("minecraft:stone", Optional.of(new CanonicalNbt(new byte[] {1, 2})));
        var after = new BlockSnapshot("minecraft:gold_block", Optional.empty());
        var blocks = new LinkedHashMap<BlockPosition, BlockDelta>();
        blocks.put(new BlockPosition(16, 1, 0), new BlockDelta(before, after));
        blocks.put(new BlockPosition(-1, -1, -1), new BlockDelta(after, before));
        UUID entity = new UUID(0, 9);
        var payload = new WorldActionRecord.Payload(blocks, Map.of(entity, new EntityDelta(Optional.empty(),
                Optional.of(new EntityState(entity, "minecraft:armor_stand", new CanonicalNbt(new byte[] {3, 4}))))));
        var record = new WorldActionRecord(metadata(1), payload);
        var bytes = new ByteArrayOutputStream();
        codec.encode(record, version, new DataOutputStream(bytes));
        byte[] valid = bytes.toByteArray();

        var restored = codec.decode(metadata(1), version, 4096, input(valid));
        assertEquals(record, restored);
        assertEquals(List.copyOf(blocks.keySet()), List.copyOf(restored.payload().blocks().keySet()));
        assertThrows(IOException.class, () -> codec.decode(metadata(2), version, 4096, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), new WorldDataVersion(5001), 4096, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 1, input(valid)));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096,
                input(Arrays.copyOf(valid, valid.length - 1))));
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096,
                input(Arrays.copyOf(valid, valid.length + 1))));
        byte[] oversizedPalette = valid.clone();
        ByteBuffer.wrap(oversizedPalette).putInt(32, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> codec.decode(metadata(1), version, 4096, input(oversizedPalette)));
    }

    private static WorldActionRecord.Metadata metadata(long revision) {
        return new WorldActionRecord.Metadata(new UUID(0, 1), new UUID(0, 2), 1, new UUID(0, 1),
                revision, true, true, false, Optional.empty());
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }
}
