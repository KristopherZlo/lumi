package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.EntityChunkBlob;
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

class RestorePlanReaderTest {
    @TempDir
    Path repository;

    @Test
    void reusesDecodedPayloadByObjectId() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId id = objects.write(section(0));
        try (var reader = new RestorePlanReader(objects)) {
            SectionBlob first = reader.readSection(id);

            assertSame(first, reader.readSection(id));
            assertEquals(1, reader.cachedSectionCount());
        }
    }

    @Test
    void evictsLeastRecentlyUsedPayloadAtTheBound() throws IOException {
        var objects = new WorldObjectRepository(repository);
        List<ObjectId> ids = new ArrayList<>();
        for (int index = 0; index <= RestorePlanReader.MAX_CACHED_SECTIONS; index++) {
            ids.add(objects.write(section(index)));
        }
        try (var reader = new RestorePlanReader(objects)) {
            List<SectionBlob> decoded = new ArrayList<>();
            for (ObjectId id : ids.subList(0, RestorePlanReader.MAX_CACHED_SECTIONS)) {
                decoded.add(reader.readSection(id));
            }
            reader.readSection(ids.getFirst());
            reader.readSection(ids.getLast());

            assertSame(decoded.getFirst(), reader.readSection(ids.getFirst()));
            assertNotSame(decoded.get(1), reader.readSection(ids.get(1)));
            assertEquals(RestorePlanReader.MAX_CACHED_SECTIONS,
                    reader.cachedSectionCount());
        }
    }

    @Test
    void readsSectionsAndEntitiesThroughOnePlanReader() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId sectionId = objects.write(section(0));
        EntityChunkBlob entities = new EntityChunkBlob(List.of());
        ObjectId entityId = objects.write(entities);

        try (var reader = new RestorePlanReader(objects)) {
            assertEquals(section(0), reader.readSection(sectionId));
            assertEquals(entities, reader.readEntities(entityId));
            assertEquals(1, reader.cachedSectionCount());
        }
    }

    @Test
    void closeIsIdempotentAndPreventsReopeningTheSession() throws IOException {
        var objects = new WorldObjectRepository(repository);
        ObjectId id = objects.write(section(0));
        var reader = new RestorePlanReader(objects);

        reader.close();
        reader.close();

        assertThrows(IOException.class, () -> reader.readSection(id));
    }

    private static SectionBlob section(int marker) {
        List<String> states = new ArrayList<>(
                java.util.Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air"));
        states.set(0, "marker:" + marker);
        return new SectionBlob(states, Map.of());
    }
}
