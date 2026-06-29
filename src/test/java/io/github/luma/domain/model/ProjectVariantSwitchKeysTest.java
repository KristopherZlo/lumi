package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectVariantSwitchKeysTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @Test
    void fillsMissingDefaultsFromOneThroughZero() {
        List<ProjectVariant> variants = new ArrayList<>();
        variants.add(new ProjectVariant("main", "main", "v0001", "v0001", true, NOW));
        for (int index = 2; index <= 11; index++) {
            variants.add(new ProjectVariant("branch-" + index, "Branch " + index, "v0001", "v0001", false, NOW.plusSeconds(index)));
        }

        List<ProjectVariant> normalized = ProjectVariantSwitchKeys.fillMissingDefaults(variants);

        assertEquals(List.of(
                "key.keyboard.1",
                "key.keyboard.2",
                "key.keyboard.3",
                "key.keyboard.4",
                "key.keyboard.5",
                "key.keyboard.6",
                "key.keyboard.7",
                "key.keyboard.8",
                "key.keyboard.9",
                "key.keyboard.0",
                ""
        ), normalized.stream().map(ProjectVariant::switchKey).toList());
    }

    @Test
    void keepsExplicitUnassignedKeyBlank() {
        List<ProjectVariant> normalized = ProjectVariantSwitchKeys.fillMissingDefaults(List.of(
                new ProjectVariant("main", "main", "v0001", "v0001", true, NOW, ""),
                new ProjectVariant("feature", "Feature", "v0001", "v0001", false, NOW.plusSeconds(1))
        ));

        assertEquals("", normalized.get(0).switchKey());
        assertEquals("key.keyboard.2", normalized.get(1).switchKey());
    }
}
