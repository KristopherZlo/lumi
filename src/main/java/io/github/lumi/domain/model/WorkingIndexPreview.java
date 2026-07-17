package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact scoped dirty count/bounds with bounded section and block coordinate subsets. */
public record WorkingIndexPreview(
        int totalKeys,
        List<SectionKey> sections,
        List<BlockPosition> blocks,
        Optional<BlockBox> bounds) {
    public WorkingIndexPreview {
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        bounds = Objects.requireNonNull(bounds, "bounds");
        if (totalKeys < 0 || sections.size() > totalKeys) {
            throw new IllegalArgumentException("Invalid working-index preview");
        }
    }

    public WorkingIndexPreview(
            int totalKeys, List<SectionKey> sections, List<BlockPosition> blocks) {
        this(totalKeys, sections, blocks, Optional.empty());
    }

    public WorkingIndexPreview(int totalKeys, List<SectionKey> sections) {
        this(totalKeys, sections, List.of(), Optional.empty());
    }
}
