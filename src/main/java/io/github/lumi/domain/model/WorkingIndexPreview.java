package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;

/** Exact scoped dirty count with a bounded subset of section coordinates. */
public record WorkingIndexPreview(int totalKeys, List<SectionKey> sections) {
    public WorkingIndexPreview {
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        if (totalKeys < 0 || sections.size() > totalKeys) {
            throw new IllegalArgumentException("Invalid working-index preview");
        }
    }
}
