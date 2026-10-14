package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionTagsTest {
    @Test
    void normalizesDeduplicatesAndSerializesBuilderInput() {
        VersionTags tags = VersionTags.parse(
                " #Roof, castle, ROOF, , ##Red Stone ");

        assertEquals(List.of("roof", "castle", "red stone"), tags.values());
        assertEquals("roof, castle, red stone", tags.serialize());
        assertEquals("#roof #castle #red stone", tags.display());
        assertEquals(VersionTags.empty(), VersionTags.parse("  "));
    }

    @Test
    void rejectsUnboundedOrAmbiguousCanonicalTags() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionTags.parse("x".repeat(
                        VersionTags.MAX_SERIALIZED_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionTags(List.of(
                        "x".repeat(VersionTags.MAX_TAG_LENGTH + 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionTags(java.util.stream.IntStream
                        .rangeClosed(0, VersionTags.MAX_TAGS)
                        .mapToObj(index -> "tag-" + index)
                        .toList()));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionTags(List.of("two,tags")));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionTags(List.of("line\nbreak")));
    }
}
