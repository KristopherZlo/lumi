package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MerkleNodeCodecTest {
    private final MerkleNodeCodec codec = new MerkleNodeCodec();
    private final ObjectId first = id("first");
    private final ObjectId second = id("second");

    @Test
    void roundTripsAllSparseNodeKinds() throws IOException {
        ChunkTree chunk = new ChunkTree(Map.of(-4, first, 20, second), Optional.of(first));
        RegionTree region = new RegionTree(Map.of(new ChunkInRegion(0, 31), first));
        DimensionTree dimension = new DimensionTree(Map.of(new RegionCoordinate(-2, 7), second));

        assertEquals(chunk, codec.decodeChunk(codec.encode(chunk)));
        assertEquals(region, codec.decodeRegion(codec.encode(region)));
        assertEquals(dimension, codec.decodeDimension(codec.encode(dimension)));
    }

    @Test
    void mapInsertionOrderCannotChangeNodeIdentity() throws IOException {
        Map<Integer, ObjectId> forward = new HashMap<>();
        forward.put(-4, first);
        forward.put(20, second);
        Map<Integer, ObjectId> reverse = new HashMap<>();
        reverse.put(20, second);
        reverse.put(-4, first);

        assertArrayEquals(
                codec.encode(new ChunkTree(forward, Optional.empty())),
                codec.encode(new ChunkTree(reverse, Optional.empty())));
    }

    @Test
    void rejectsWrongNodeKindAndTrailingData() throws IOException {
        byte[] chunk = codec.encode(new ChunkTree(Map.of(), Optional.empty()));
        byte[] trailing = java.util.Arrays.copyOf(chunk, chunk.length + 1);

        assertThrows(IOException.class, () -> codec.decodeRegion(chunk));
        assertThrows(IOException.class, () -> codec.decodeChunk(trailing));
    }

    private static ObjectId id(String value) {
        return ObjectId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
