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
    }
}
