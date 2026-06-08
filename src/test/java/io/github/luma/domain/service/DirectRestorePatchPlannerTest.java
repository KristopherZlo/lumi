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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DirectRestorePatchPlannerTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:00:00Z");

    private final DirectRestorePatchPlanner planner = new DirectRestorePatchPlanner();

    @Test
    void acceptsSharedAncestorFromBranchBase() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "main", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0003", "v0004", false, NOW)
        );

        List<ProjectVersion> direct = this.planner.patchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(2)
        );

        assertNotNull(direct);
        assertEquals(List.of("v0004"), direct.stream().map(ProjectVersion::id).toList());
    }

    @Test
    void rejectsDetachedTargetOutsideActiveLineage() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "main", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0003", "v0004", false, NOW)
        );

        List<ProjectVersion> direct = this.planner.patchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(1)
        );

        assertNull(direct);
    }

    @Test
    void supportsDivergentBranchHeadThroughCommonAncestor() {
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "feature", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0004", false, NOW)
        );

        DirectRestorePatchPlan plan = this.planner.plan(project("main"), versions, variants, versions.get(3));

        assertNotNull(plan);
        assertEquals(List.of("v0002"), plan.reverseVersions().stream().map(ProjectVersion::id).toList());
        assertEquals(List.of("v0003", "v0004"), plan.forwardVersions().stream().map(ProjectVersion::id).toList());
        assertNull(this.planner.patchVersions(project("main"), versions, variants, versions.get(3)));
        assertNull(this.planner.applicablePlan(project("main"), versions, variants, versions.get(3)));
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

    private static ProjectVersion version(String id, String variantId, String parentVersionId) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                parentVersionId,
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
