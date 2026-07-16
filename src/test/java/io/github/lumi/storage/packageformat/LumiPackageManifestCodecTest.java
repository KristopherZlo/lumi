package io.github.lumi.storage.packageformat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LumiPackageManifestCodecTest {
    private final LumiPackageManifestCodec codec = new LumiPackageManifestCodec();

    @Test
    void roundTripsInCanonicalObjectOrder() throws Exception {
        Map<ObjectId, Integer> first = new LinkedHashMap<>();
        first.put(id('b'), 20);
        first.put(id('a'), 10);
        Map<ObjectId, Integer> second = new LinkedHashMap<>();
        second.put(id('a'), 10);
        second.put(id('b'), 20);
        var left = new LumiPackageManifest(
                "minecraft:overworld", new CommitId(id('c')), 40, first);
        var right = new LumiPackageManifest(
                "minecraft:overworld", new CommitId(id('c')), 40, second);

        assertArrayEquals(codec.encode(left), codec.encode(right));
        assertEquals(left, codec.decode(codec.encode(left)));
    }

    @Test
    void rejectsTruncationAndUnsafeDeclaredSizes() throws Exception {
        byte[] encoded = codec.encode(new LumiPackageManifest(
                "minecraft:overworld", new CommitId(id('c')), 40,
                Map.of(id('a'), 10)));

        assertThrows(IOException.class,
                () -> codec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> new LumiPackageManifest(
                "minecraft:overworld", new CommitId(id('c')), 40,
                Map.of(id('a'), LumiPackageManifest.MAX_ENTRY_BYTES + 1)));
    }

    private static ObjectId id(char digit) {
        return new ObjectId(String.valueOf(digit).repeat(64));
    }
}
