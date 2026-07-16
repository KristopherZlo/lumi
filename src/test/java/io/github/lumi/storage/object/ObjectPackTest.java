package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ObjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(),
                    ObjectPack.read(published.entries().get(entry.getKey())));
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
    }
}
