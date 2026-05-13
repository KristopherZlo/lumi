package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreRequestResolverTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");
    private final RestoreRequestResolver resolver = new RestoreRequestResolver();

    @Test
    void explicitVersionIdWinsOverActiveVariantHead() {
        ProjectVersion target = version("v0001", "main");
        ProjectVersion activeHead = version("v0002", "main");

        ProjectVersion resolved = this.resolver.resolveVersion(
                project("main"),
                List.of(target, activeHead),
                List.of(new ProjectVariant("main", "main", "v0001", "v0002", true, NOW)),
                "v0001"
        );

        assertEquals("v0001", resolved.id());
    }

    @Test
    void blankVersionIdUsesActiveVariantHead() {
        ProjectVersion activeHead = version("v0002", "main");

        ProjectVersion resolved = this.resolver.resolveVersion(
                project("main"),
                List.of(version("v0001", "main"), activeHead),
                List.of(new ProjectVariant("main", "main", "v0001", "v0002", true, NOW)),
                ""
        );

        assertEquals(activeHead, resolved);
    }

    @Test
    void missingActiveVariantIsRejectedWhenVersionIdIsBlank() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> this.resolver.resolveVersion(
                project("missing"),
                List.of(version("v0001", "main")),
                List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, NOW)),
                null
        ));

        assertEquals("Active variant is missing for project", thrown.getMessage());
    }

    @Test
    void targetVariantDefaultsToResolvedVersionsBranch() {
        ProjectVariant feature = new ProjectVariant("feature", "feature", "v0001", "v0002", false, NOW);

        ProjectVariant resolved = this.resolver.restoreTargetVariant(
                List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, NOW), feature),
                version("v0002", "feature"),
                ""
        );

        assertEquals(feature, resolved);
    }

    @Test
    void explicitTargetVariantOverridesVersionBranch() {
        ProjectVariant main = new ProjectVariant("main", "main", "v0001", "v0001", true, NOW);

        ProjectVariant resolved = this.resolver.restoreTargetVariant(
                List.of(main, new ProjectVariant("feature", "feature", "v0001", "v0002", false, NOW)),
                version("v0002", "feature"),
                "main"
        );

        assertEquals(main, resolved);
    }

    private static BuildProject project(String activeVariantId) {
        return BuildProject.create(
                        "project",
                        "minecraft:overworld",
                        new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(1, 1, 1)),
                        new BlockPoint(0, 0, 0),
                        NOW
                )
                .withActiveVariantId(activeVariantId, NOW);
    }

    private static ProjectVersion version(String id, String variantId) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                "",
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }
}
