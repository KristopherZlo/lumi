package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAndReopensCompressedCanonicalPayload() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] payload = "canonical world state".getBytes(StandardCharsets.UTF_8);

        ObjectId id = store.write(payload);

        assertEquals(ObjectId.hash(payload), id);
        assertArrayEquals(payload, store.read(id));
    }

    @Test
    void identicalPayloadIsDeduplicated() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] payload = "same".getBytes(StandardCharsets.UTF_8);

        assertEquals(store.write(payload), store.write(payload));
        try (var files = Files.walk(tempDir)) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectsTruncatedOrCorruptObjects() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        ObjectId id = store.write("state".getBytes(StandardCharsets.UTF_8));
        Path objectFile;
        try (var files = Files.walk(tempDir)) {
            objectFile = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        byte[] stored = Files.readAllBytes(objectFile);
        Files.write(objectFile, java.util.Arrays.copyOf(stored, stored.length - 1));

        assertThrows(CorruptObjectException.class, () -> store.read(id));
    }

    @Test
    void batchPublishesPackedObjectsWithoutOneFilePerPayload() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] loose = "loose".getBytes(StandardCharsets.UTF_8);
        ObjectId looseId = store.write(loose);
        Map<ObjectId, byte[]> expected = new LinkedHashMap<>();
        expected.put(looseId, loose);

        try (ObjectStore.WriteBatch batch = store.beginBatch()) {
            assertEquals(looseId, batch.write(loose));
            for (int index = 0; index < 128; index++) {
                byte[] payload = ("packed-" + index).getBytes(StandardCharsets.UTF_8);
                expected.put(batch.write(payload), payload);
            }
            batch.publish();
        }

        store = new ObjectStore(tempDir);
        assertEquals(expected.keySet(), store.listIds());
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), store.read(entry.getKey()));
        }
        try (var files = Files.walk(tempDir)) {
            assertEquals(3, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void existingStoreDiscoversPackPublishedByAnotherRepositoryInstance()
            throws IOException {
        ObjectStore writer = new ObjectStore(tempDir);
        ObjectStore reader = new ObjectStore(tempDir);
        assertTrue(reader.listIds().isEmpty());
        byte[] payload = "shared pack".getBytes(StandardCharsets.UTF_8);
        ObjectId id;
        try (ObjectStore.WriteBatch batch = writer.beginBatch()) {
            id = batch.write(payload);
            batch.publish();
        }

        assertArrayEquals(payload, reader.read(id));
        assertEquals(Set.of(id), reader.listIds());
        try (ObjectStore.WriteBatch batch = reader.beginBatch()) {
            assertEquals(id, batch.write(payload));
            batch.publish();
        }
        try (var files = Files.walk(tempDir.resolve("packs"))) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".pack")).count());
        }
    }

    @Test
    void recreatesPackedPayloadDeletedByAnotherRepositoryInstance()
            throws IOException {
        byte[] payload = "reused world state".getBytes(StandardCharsets.UTF_8);
        ObjectStore active = new ObjectStore(tempDir);
        ObjectId id;
        try (ObjectStore.WriteBatch batch = active.beginBatch()) {
            id = batch.write(payload);
            batch.publish();
        }
        assertArrayEquals(payload, active.read(id));

        assertEquals(1, new ObjectStore(tempDir).deleteAll(Set.of(id)));
        try (ObjectStore.WriteBatch batch = active.beginBatch()) {
            assertEquals(id, batch.write(payload));
            batch.publish();
        }

        assertArrayEquals(payload, active.read(id));
    }

    @Test
    void publishesARealisticDirtySectionBatchAsOnePack() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        Map<ObjectId, byte[]> expected = new LinkedHashMap<>();
        try (ObjectStore.WriteBatch batch = store.beginBatch()) {
            for (int index = 0; index < 1_644; index++) {
                byte[] payload = ("section-" + index + "-"
                        + "state,".repeat(512)).getBytes(StandardCharsets.UTF_8);
                expected.put(batch.write(payload), payload);
            }
            batch.publish();
        }

        assertEquals(expected.keySet(), store.listIds());
        try (var files = Files.walk(tempDir)) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void deletesAnImmutablePackOnlyWhenEveryEntryIsCollectable() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        ObjectId first;
        ObjectId second;
        try (ObjectStore.WriteBatch batch = store.beginBatch()) {
            first = batch.write("first".getBytes(StandardCharsets.UTF_8));
            second = batch.write("second".getBytes(StandardCharsets.UTF_8));
            batch.publish();
        }

        assertEquals(0, store.deleteAll(Set.of(first)));
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), store.read(first));
        assertEquals(2, store.deleteAll(Set.of(first, second)));
        assertTrue(store.listIds().isEmpty());
    }

    @Test
    void compactsSelectedLooseObjectsAndFinishesPublishedDuplicates()
            throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] duplicate = "published duplicate".getBytes(StandardCharsets.UTF_8);
        byte[] migrate = "legacy loose".getBytes(StandardCharsets.UTF_8);
        byte[] untouched = "fresh orphan".getBytes(StandardCharsets.UTF_8);
        ObjectId duplicateId = store.write(duplicate);
        ObjectId migrateId = store.write(migrate);
        ObjectId untouchedId = store.write(untouched);
        try (ObjectPack.Writer writer = ObjectPack.writer(tempDir.resolve("packs"))) {
            assertEquals(duplicateId, writer.write(duplicate));
            writer.publish();
        }

        store.compactLoose(Set.of(duplicateId, migrateId));

        assertFalse(Files.exists(loosePath(duplicateId)));
        assertFalse(Files.exists(loosePath(migrateId)));
        assertTrue(Files.exists(loosePath(untouchedId)));
        assertArrayEquals(duplicate, new ObjectStore(tempDir).read(duplicateId));
        assertArrayEquals(migrate, new ObjectStore(tempDir).read(migrateId));
        assertArrayEquals(untouched, new ObjectStore(tempDir).read(untouchedId));
        long packCount;
        try (var files = Files.list(tempDir.resolve("packs"))) {
            packCount = files.filter(path -> path.toString().endsWith(".pack")).count();
        }
        store.compactLoose(Set.of(duplicateId, migrateId));
        try (var files = Files.list(tempDir.resolve("packs"))) {
            assertEquals(packCount,
                    files.filter(path -> path.toString().endsWith(".pack")).count());
        }
    }

    @Test
    void retainsLooseDuplicateWhenPublishedPackIsCorrupt() throws IOException {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] payload = "valid loose fallback".getBytes(StandardCharsets.UTF_8);
        ObjectId id = store.write(payload);
        byte[] looseFile = Files.readAllBytes(loosePath(id));
        ObjectPack.Published published;
        try (ObjectPack.Writer writer = ObjectPack.writer(tempDir.resolve("packs"))) {
            writer.write(payload);
            published = writer.publish();
        }
        Path pack = published.entries().get(id).pack();
        byte[] corrupt = Files.readAllBytes(pack);
        corrupt[corrupt.length - 1] ^= 0x7f;
        Files.write(pack, corrupt);

        assertThrows(CorruptObjectException.class,
                () -> store.compactLoose(Set.of(id)));

        assertTrue(Files.exists(loosePath(id)));
        assertArrayEquals(looseFile, Files.readAllBytes(loosePath(id)));
    }

    private Path loosePath(ObjectId id) {
        return tempDir.resolve(id.hex().substring(0, 2))
                .resolve(id.hex().substring(2) + ".lz4");
    }
}
