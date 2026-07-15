package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionBlobCodecTest {
    private final SectionBlobCodec codec = new SectionBlobCodec();

    @Test
    void roundTripsExactSectionState() throws IOException {
        List<String> states = states();
        states.set(17, "minecraft:stone");
        states.set(4095, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        SectionBlob section = new SectionBlob(states, Map.of(
                17, new CanonicalNbt(new byte[] {1, 2, 3}),
                4095, new CanonicalNbt(new byte[] {4, 5})));

        SectionBlob decoded = codec.decode(codec.encode(section));

        assertEquals(section, decoded);
    }

    @Test
    void mapInsertionOrderCannotChangeIdentity() throws IOException {
        Map<Integer, CanonicalNbt> first = new HashMap<>();
        first.put(9, new CanonicalNbt(new byte[] {9}));
        first.put(2, new CanonicalNbt(new byte[] {2}));
        Map<Integer, CanonicalNbt> second = new HashMap<>();
        second.put(2, new CanonicalNbt(new byte[] {2}));
        second.put(9, new CanonicalNbt(new byte[] {9}));

        assertArrayEquals(
                codec.encode(new SectionBlob(states(), first)),
                codec.encode(new SectionBlob(states(), second)));
    }

    @Test
    void rejectsTrailingOrMalformedData() throws IOException {
        byte[] valid = codec.encode(new SectionBlob(states(), Map.of()));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThrows(IOException.class, () -> codec.decode(trailing));
        assertThrows(IOException.class, () -> codec.decode(new byte[] {1, 2, 3}));
    }

    private static List<String> states() {
        return new ArrayList<>(java.util.Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air"));
    }
}
