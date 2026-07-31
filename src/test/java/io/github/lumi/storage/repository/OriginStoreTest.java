package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginStoreTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void originCanOnlyBeCreatedOnce() throws IOException {
        OriginStore store = new OriginStore(repositoryRoot);
        SectionKey key = new SectionKey(-2, 7, 9);
        ObjectId first = id("before");

        assertTrue(store.register(key, first));
        assertFalse(store.register(key, first));
        assertThrows(OriginConflictException.class, () -> store.register(key, id("different")));
        assertEquals(first, new OriginStore(repositoryRoot).read(key).orElseThrow());
    }

    @Test
    void sectionAndEntityOriginsUseDistinctKeys() throws IOException {
        OriginStore store = new OriginStore(repositoryRoot);
        SectionKey section = new SectionKey(4, -1, 8);
        EntityChunkKey entities = new EntityChunkKey(4, 8);

        store.register(section, id("section"));
        store.register(entities, id("entities"));

        assertEquals(id("section"), store.read(section).orElseThrow());
        assertEquals(id("entities"), store.read(entities).orElseThrow());
        assertEquals(Map.of(section, id("section"), entities, id("entities")),
                new OriginStore(repositoryRoot).entries());
        assertEquals(Map.of(entities, id("entities")),
                new OriginStore(repositoryRoot).entityEntries());
        assertEquals(Set.of(section, entities),
                new OriginStore(repositoryRoot).keys());
    }

    @Test
    void batchStoresManyOriginsInOneRegionShard() throws IOException {
        OriginStore store = new OriginStore(repositoryRoot);
        Map<HistoryKey, ObjectId> expected = new HashMap<>();
        for (int index = 0; index < 1_000; index++) {
            SectionKey key = new SectionKey(index % 32, index / 32, index % 31);
            expected.put(key, id("section-" + index));
        }

        assertEquals(expected.size(), store.registerAll(expected));
        assertEquals(0, store.registerAll(expected));
        try (var files = Files.walk(repositoryRoot.resolve("origins"))) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
        OriginStore reopened = new OriginStore(repositoryRoot);
        assertEquals(expected, reopened.entries());
        assertEquals(expected.keySet(), reopened.keys());
        assertThrows(OriginConflictException.class, () ->
                reopened.register(expected.keySet().iterator().next(), id("different")));
    }

    @Test
    void legacyEntriesRemainReadableBesideRegionShards() throws IOException {
        SectionKey legacy = new SectionKey(-33, 4, 63);
        ObjectId legacyId = id("legacy");
        Path legacyFile = repositoryRoot.resolve("origins/sections/-33/63/4.origin");
        Files.createDirectories(legacyFile.getParent());
        Files.write(legacyFile, legacyPayload(legacy, legacyId));

        OriginStore store = new OriginStore(repositoryRoot);
        EntityChunkKey sharded = new EntityChunkKey(-33, 63);
        assertFalse(store.register(legacy, legacyId));
        assertTrue(store.register(sharded, id("sharded")));
        assertEquals(Map.of(legacy, legacyId, sharded, id("sharded")),
                new OriginStore(repositoryRoot).entries());
        assertThrows(OriginConflictException.class, () ->
                store.register(legacy, id("different")));
    }

    @Test
    void keyScanRejectsMalformedCanonicalPathOrPayloadSize() throws IOException {
        OriginStore store = new OriginStore(repositoryRoot);
        store.register(new SectionKey(1, 2, 3), id("section"));
        Path invalid = repositoryRoot.resolve("origins/sections/01/3/2.origin");
        Files.createDirectories(invalid.getParent());
        Files.write(invalid, new byte[49]);

        assertThrows(IOException.class, store::keys);

        Files.delete(invalid);
        Path truncated = repositoryRoot.resolve("origins/entities/4/5.origin");
        Files.createDirectories(truncated.getParent());
        Files.write(truncated, new byte[44]);
        assertThrows(IOException.class, store::keys);

        Files.delete(truncated);
        Path shard = repositoryRoot.resolve("origins/regions/0/0.bin");
        byte[] corrupted = Files.readAllBytes(shard);
        ByteBuffer.wrap(corrupted).putInt(Integer.BYTES, 1);
        Files.write(shard, corrupted);
        assertThrows(IOException.class, () -> new OriginStore(repositoryRoot).keys());
    }

    private static byte[] legacyPayload(SectionKey key, ObjectId id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4C4F5232);
            output.writeByte(1);
            output.writeInt(key.chunkX());
            output.writeInt(key.sectionY());
            output.writeInt(key.chunkZ());
            output.write(HexFormat.of().parseHex(id.hex()));
        }
        return bytes.toByteArray();
    }

    private static ObjectId id(String value) {
        return ObjectId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
