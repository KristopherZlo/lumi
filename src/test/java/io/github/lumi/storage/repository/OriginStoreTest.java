package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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
    }

    private static ObjectId id(String value) {
        return ObjectId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
