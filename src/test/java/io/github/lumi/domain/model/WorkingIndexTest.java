package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkingIndexTest {
    @Test
    void previewsExactScopedCountWithBoundedSectionCoordinates() {
        WorkingIndex index = new WorkingIndex();
        SectionKey first = new SectionKey(0, 1, 2);
        SectionKey second = new SectionKey(3, 4, 5);
        index.markDirty(first);
        index.markDirty(second);
        index.markDirty(new EntityChunkKey(6, 7));

        WorkingIndexPreview preview = index.preview(
                key -> !(key instanceof SectionKey section) || section.chunkX() >= 3, 1);

        assertEquals(2, preview.totalKeys());
        assertEquals(java.util.List.of(second), preview.sections());
        assertEquals(new BlockBox(48, 64, 80, 63, 79, 95),
                preview.bounds().orElseThrow());
    }

    @Test
    void aggregatesRepresentableSectionBoundaryCoordinatesWithoutOverflow() {
        WorkingIndex index = new WorkingIndex();
        index.markDirty(new SectionKey(134_217_727, 0, -134_217_728));

        assertEquals(new BlockBox(
                        2_147_483_632, 0, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, 15, -2_147_483_633),
                index.preview(ignored -> true, 0).bounds().orElseThrow());
    }
}
