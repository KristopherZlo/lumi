package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ObjectIdTest {
    @Test
    void hashesCanonicalBytesWithSha256() {
        ObjectId id = ObjectId.hash("abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", id.hex());
    }

    @Test
    void rejectsNonCanonicalText() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectId("ABC"));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId("g".repeat(64)));
    }
}
