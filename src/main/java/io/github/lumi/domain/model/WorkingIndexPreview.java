package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;

/** Exact scoped dirty count with bounded subsets of section and block coordinates. */
public record WorkingIndexPreview(
        int totalKeys,
        List<SectionKey> sections,
        List<BlockPosition> blocks) {
    public WorkingIndexPreview {
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (totalKeys < 0 || sections.size() > totalKeys) {
            throw new IllegalArgumentException("Invalid working-index preview");
        }
    }

    public WorkingIndexPreview(int totalKeys, List<SectionKey> sections) {
        this(totalKeys, sections, List.of());
    }
}
