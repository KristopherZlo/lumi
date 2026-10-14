package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockBoxTest {
    @Test
    void convertsInclusiveBlocksToBoundedSectionCells() {
        BlockBox box = new BlockBox(-1, 0, 15, 16, 16, 16);

        assertEquals(Set.of(
                new SectionKey(-1, 0, 0), new SectionKey(-1, 0, 1),
                new SectionKey(-1, 1, 0), new SectionKey(-1, 1, 1),
                new SectionKey(0, 0, 0), new SectionKey(0, 0, 1),
                new SectionKey(0, 1, 0), new SectionKey(0, 1, 1),
                new SectionKey(1, 0, 0), new SectionKey(1, 0, 1),
                new SectionKey(1, 1, 0), new SectionKey(1, 1, 1)),
                box.sectionCells(12));
        assertThrows(IllegalArgumentException.class, () -> box.sectionCells(11));
    }
}
