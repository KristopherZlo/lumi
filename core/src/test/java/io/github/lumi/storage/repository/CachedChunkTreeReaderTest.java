package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachedChunkTreeReaderTest {
    @Test
    void repeatedImmutableNodesAreDecodedOnceAndFailuresAreRetried() throws Exception {
        var reads = new AtomicInteger();
        var node = new ChunkTree(Map.of(), Optional.empty());
        var reader = new CachedChunkTreeReader(id -> {
            if (reads.incrementAndGet() == 1) throw new IOException("Corrupt pack");
            return node;
        });
        assertThrows(IOException.class, () -> reader.read(id(1)));
        long start = System.nanoTime();
        for (int count = 0; count < 1024; count++) assertSame(node, reader.read(id(1)));
        System.out.println("Save repeated chunk metadata: ns=" + (System.nanoTime() - start)
                + ", physical reads=" + reads.get());
        assertEquals(2, reads.get());
    }

    @Test
    void childReferenceBudgetEvictsLeastRecentlyUsedNodes() throws Exception {
        var reads = new AtomicInteger();
        Map<Integer, ObjectId> sections = new HashMap<>();
        for (int index = 0; index < 2047; index++) sections.put(index, id(0));
        var reader = new CachedChunkTreeReader(id -> {
            reads.incrementAndGet();
            return new ChunkTree(sections, Optional.empty());
        });
        reader.read(id(1));
        reader.read(id(2));
        reader.read(id(1));
        reader.read(id(3));
        reader.read(id(2));
        assertEquals(4, reads.get());
    }

    @Test
    void sparseNodesAreBoundedByCountAndOversizedNodesBypassCache() throws Exception {
        var reads = new AtomicInteger();
        var reader = new CachedChunkTreeReader(id -> {
            reads.incrementAndGet();
            return new ChunkTree(Map.of(), Optional.empty());
        });
        for (int index = 0; index < 257; index++) reader.read(id(index));
        reader.read(id(0));
        assertEquals(258, reads.get());
        Map<Integer, ObjectId> sections = new HashMap<>();
        for (int index = 0; index < 4096; index++) sections.put(index, id(0));
        var large = new CachedChunkTreeReader(id -> {
            reads.incrementAndGet();
            return new ChunkTree(sections, Optional.empty());
        });
        large.read(id(0));
        large.read(id(0));
        assertEquals(260, reads.get());
    }

    private static ObjectId id(int value) {
        return new ObjectId(String.format("%064x", value));
    }
}
