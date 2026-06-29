package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VariantRepositoryTest {

    @TempDir
    Path tempDir;

    private final VariantRepository repository = new VariantRepository();

    @Test
    void savesVariantsAtomicallyAndRoundTrips() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Variants");
        List<ProjectVariant> variants = List.of(new ProjectVariant(
                "main",
                "main",
                "v0001",
                "v0002",
                true,
                Instant.parse("2026-04-28T08:00:00Z")
        ));

        this.repository.save(layout, variants);

        assertEquals(variants, this.repository.loadAll(layout));
        assertFalse(Files.exists(layout.variantsFile().resolveSibling("variants.json.tmp")));
    }

    @Test
    void loadsLegacyBranchObjectFormat() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "LegacyVariants");
        Files.createDirectories(layout.root());
        Files.writeString(layout.variantsFile(), """
                {
                  "schemaVersion": 1,
                  "branches": [
                    {
                      "id": "main",
                      "displayName": "Main",
                      "baseVersionId": "WORLD_ROOT",
                      "headVersionId": "save-gametest-restore",
                      "main": true,
                      "createdAt": "2026-06-22T14:00:00Z"
                    }
                  ]
                }
                """);

        assertEquals(List.of(new ProjectVariant(
                "main",
                "Main",
                "WORLD_ROOT",
                "save-gametest-restore",
                true,
                Instant.parse("2026-06-22T14:00:00Z")
        )), this.repository.loadAll(layout));
    }
}
