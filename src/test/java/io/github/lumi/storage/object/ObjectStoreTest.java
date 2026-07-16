package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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

        assertEquals(expected.keySet(), store.listIds());
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), store.read(entry.getKey()));
        }
        try (var files = Files.walk(tempDir)) {
            assertEquals(3, files.filter(Files::isRegularFile).count());
        }
    }
}
