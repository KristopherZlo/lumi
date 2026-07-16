package io.github.lumi.storage.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectPackIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyPublishesAndReopensPackLocations() throws Exception {
        Path pack = tempDir.resolve("save.pack");
        Files.write(pack, new byte[8]);
        ObjectId id = ObjectId.hash(new byte[] {1, 2, 3});
        Map<ObjectId, PackedObject> expected = Map.of(
                id, new PackedObject(id, pack, 8, 3, 4));
        Path index = tempDir.resolve("save.idx");

        ObjectPackIndex.write(index, expected);

        assertEquals(expected, ObjectPackIndex.read(index));
        assertEquals(expected, ObjectPackIndex.load(tempDir));
    }
}
