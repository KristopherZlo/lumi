package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import java.time.Instant;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RestoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void collapsePreparedBatchesKeepsOnlyLastPlacementPerBlock() {
        PreparedChunkBatch first = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.STONE.defaultBlockState(), null),
                        new PreparedBlockPlacement(new BlockPos(2, 64, 2), Blocks.DIRT.defaultBlockState(), null)
                )
        );
        PreparedChunkBatch second = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.GOLD_BLOCK.defaultBlockState(), null)
                )
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(new BlockPos(1, 64, 1), collapsed.getFirst().placements().getFirst().pos());
    }

    @Test
    void collapsePreparedBatchesKeepsEntityOnlyBatches() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:block_display");
        entity.putString("UUID", "00000000-0000-0000-0000-000000000050");
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                new EntityBatch(List.of(entity), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToSpawn().size());
    }

    @Test
    void directRestoreAcceptsSharedAncestorFromBranchBase() {
        RestoreService service = new RestoreService();
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

        List<ProjectVersion> direct = service.directRestorePatchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(2)
        );

        assertNotNull(direct);
        assertEquals(List.of("v0004"), direct.stream().map(ProjectVersion::id).toList());
    }

    @Test
    void directRestoreRejectsDetachedTargetOutsideActiveLineage() {
        RestoreService service = new RestoreService();
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

        List<ProjectVersion> direct = service.directRestorePatchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(1)
        );

        assertNull(direct);
    }

    @Test
    void directRestorePlanSupportsDivergentBranchHeadThroughCommonAncestor() {
        RestoreService service = new RestoreService();
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

        RestoreService.DirectRestorePatchPlan plan = service.directRestorePatchPlan(
                project("main"),
                versions,
                variants,
                versions.get(3)
        );

        assertNotNull(plan);
        assertEquals(List.of("v0002"), plan.reverseVersions().stream().map(ProjectVersion::id).toList());
        assertEquals(List.of("v0003", "v0004"), plan.forwardVersions().stream().map(ProjectVersion::id).toList());
        assertNull(service.directRestorePatchVersions(project("main"), versions, variants, versions.get(3)));
    }

    @Test
    void restoreTargetCanUseExplicitBranchWhenHeadVersionBelongsToMain() {
        RestoreService service = new RestoreService();
        ProjectVersion baseVersion = version("v0001", "main", "");
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0001", false, NOW)
        );

        ProjectVariant target = service.restoreTargetVariant(variants, baseVersion, "feature");

        assertEquals("feature", target.id());
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
