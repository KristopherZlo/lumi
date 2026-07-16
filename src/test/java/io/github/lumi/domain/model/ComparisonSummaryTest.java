package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparisonSummaryTest {
    @Test
    void keepsAnImmutableMaterialSummary() {
        CommitId before = new CommitId(new ObjectId("1".repeat(ObjectId.HEX_LENGTH)));
        CommitId after = new CommitId(new ObjectId("2".repeat(ObjectId.HEX_LENGTH)));
        Map<String, MaterialDelta> materials = new java.util.HashMap<>();
        materials.put("minecraft:stone", new MaterialDelta(3, 8));
        var sections = new java.util.ArrayList<>(
                java.util.List.of(new SectionKey(1, 2, 3)));

        ComparisonSummary summary = new ComparisonSummary(
                before, after, 2, 1, sections, materials);
        materials.clear();
        sections.clear();

        assertEquals(5, summary.materials().get("minecraft:stone").change());
        assertEquals(java.util.List.of(new SectionKey(1, 2, 3)),
                summary.sectionPreview());
        assertThrows(UnsupportedOperationException.class,
                () -> summary.materials().clear());
    }

    @Test
    void rejectsInvalidCountsAndSameCommit() {
        CommitId id = new CommitId(new ObjectId("3".repeat(ObjectId.HEX_LENGTH)));

        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonSummary(id, id, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonSummary(
                        id,
                        new CommitId(new ObjectId("4".repeat(ObjectId.HEX_LENGTH))),
                        -1, 0, Map.of()));
    }
}
