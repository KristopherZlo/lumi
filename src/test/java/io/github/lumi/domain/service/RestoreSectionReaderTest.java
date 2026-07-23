package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreSectionReaderTest {
    @TempDir
    Path repository;

    @Test
    void reusesDecodedPayloadByObjectId() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId id = objects.write(section(0));
        var reader = new RestoreSectionReader(objects);

        SectionBlob first = reader.read(id);

        assertSame(first, reader.read(id));
        assertEquals(1, reader.cachedSectionCount());
    }

    @Test
    void evictsLeastRecentlyUsedPayloadAtTheBound() throws IOException {
        var objects = new WorldObjectRepository(repository);
        List<ObjectId> ids = new ArrayList<>();
        for (int index = 0; index <= RestoreSectionReader.MAX_CACHED_SECTIONS; index++) {
            ids.add(objects.write(section(index)));
        }
        var reader = new RestoreSectionReader(objects);
        List<SectionBlob> decoded = new ArrayList<>();
        for (ObjectId id : ids.subList(0, RestoreSectionReader.MAX_CACHED_SECTIONS)) {
            decoded.add(reader.read(id));
        }
        reader.read(ids.getFirst());
        reader.read(ids.getLast());

        assertSame(decoded.getFirst(), reader.read(ids.getFirst()));
        assertNotSame(decoded.get(1), reader.read(ids.get(1)));
        assertEquals(RestoreSectionReader.MAX_CACHED_SECTIONS,
                reader.cachedSectionCount());
    }

    private static SectionBlob section(int marker) {
        List<String> states = new ArrayList<>(
                java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air"));
        states.set(0, "marker:" + marker);
        return new SectionBlob(states, Map.of());
    }
}
