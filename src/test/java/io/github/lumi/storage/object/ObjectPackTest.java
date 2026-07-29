package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ObjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectPackTest {
    @TempDir
    Path tempDir;

    @Test
    void publishesManyVerifiedObjectsWithOnePackAndOneIndex() throws Exception {
        Map<ObjectId, byte[]> expected = new LinkedHashMap<>();
        ObjectPack.Published published;
        try (ObjectPack.Writer writer = ObjectPack.writer(tempDir)) {
            for (int index = 0; index < 128; index++) {
                byte[] payload = ("section-" + index).getBytes(StandardCharsets.UTF_8);
                expected.put(writer.write(payload), payload);
            }
            assertEquals(writer.write(expected.values().iterator().next()),
                    expected.keySet().iterator().next());
            published = writer.publish();
        }

        assertEquals(expected.keySet(), published.entries().keySet());
        assertEquals(expected.keySet(), ObjectPack.load(tempDir).keySet());
        try (var reader = new ObjectPack.Reader()) {
            for (var entry : expected.entrySet()) {
                assertArrayEquals(entry.getValue(),
                        reader.read(published.entries().get(entry.getKey())));
            }
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void readerReusesAlternatingPublishedPacks() throws Exception {
        Map<ObjectId, byte[]> expected = new LinkedHashMap<>();
        Map<ObjectId, PackedObject> packed = new LinkedHashMap<>();
        for (int packIndex = 0; packIndex < 3; packIndex++) {
            try (ObjectPack.Writer writer = ObjectPack.writer(tempDir)) {
                for (int objectIndex = 0; objectIndex < 2; objectIndex++) {
                    byte[] payload = ("pack-" + packIndex + "-" + objectIndex)
                            .getBytes(StandardCharsets.UTF_8);
                    ObjectId id = writer.write(payload);
                    expected.put(id, payload);
                }
                packed.putAll(writer.publish().entries());
            }
        }

        try (var reader = new ObjectPack.Reader()) {
            var ids = new ArrayList<>(expected.keySet());
            for (ObjectId id : List.of(
                    ids.get(0), ids.get(2), ids.get(4),
                    ids.get(1), ids.get(3), ids.get(5))) {
                assertArrayEquals(expected.get(id), reader.read(packed.get(id)));
            }
        }
    }
}
