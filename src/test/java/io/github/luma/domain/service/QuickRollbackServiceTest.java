package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.minecraft.world.MechanismReplayScope;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.storage.ProjectLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickRollbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-31T00:00:00Z");

    @Test
    void fullQuickRollbackUsesMechanismHaloPositions() {
        QuickRollbackService service = new QuickRollbackService();
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(1, 64, 1), new BlockPoint(2, 64, 1)),
                List.of()
        );

        List<BlockPoint> positions = service.mechanismReconciliationPositions(project, scope, null, null);

        assertEquals(List.of(new BlockPoint(1, 64, 1), new BlockPoint(2, 64, 1)), positions);
    }

    @Test
    void selectedQuickRollbackNeverWritesOutsideSelection() {
        QuickRollbackService service = new QuickRollbackService();
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(1, 64, 1), new BlockPoint(8, 64, 1)),
                List.of()
        );

        List<BlockPoint> positions = service.mechanismReconciliationPositions(
                project,
                scope,
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 80, 5)),
                null
        );

        assertTrue(positions.contains(new BlockPoint(1, 64, 1)));
        assertFalse(positions.contains(new BlockPoint(8, 64, 1)));
    }

    @Test
    void fullQuickRollbackNeverReconcilesMechanismHaloOutsideProjectBounds() {
        QuickRollbackService service = new QuickRollbackService();
        BuildProject project = BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(5, 80, 5)),
                new BlockPoint(0, 0, 0),
                NOW
        );
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(1, 64, 1), new BlockPoint(8, 64, 1)),
                List.of()
        );

        List<BlockPoint> positions = service.mechanismReconciliationPositions(project, scope, null, null);

        assertEquals(List.of(new BlockPoint(1, 64, 1)), positions);
    }

    @Test
    void selectedRollbackConsumesOnlyFullyCoveredDirtySections() {
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "head");
        ChunkSectionPoint inside = new ChunkSectionPoint(0, 0, 4);
        ChunkSectionPoint outside = new ChunkSectionPoint(1, 0, 4);
        scope.markBlockSections(List.of(inside, outside));
        scope.markEntityChunk(new ChunkPoint(0, 0));

        ProjectDirtyScope.Split fullCell = QuickRollbackService.splitDirtyScopeForBounds(
                scope,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 79, 15))
        );
        ProjectDirtyScope.Split partialCell = QuickRollbackService.splitDirtyScopeForBounds(
                scope,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(5, 70, 5))
        );

        assertEquals(List.of(inside), fullCell.selected().blockSections().stream().toList());
        assertEquals(List.of(outside), fullCell.remainder().blockSections().stream().toList());
        assertTrue(partialCell.remainder().blockSections().contains(inside));
        assertTrue(fullCell.remainder().entityChunks().contains(new ChunkPoint(0, 0)));
    }

    @Test
    void quickRollbackSkipsMechanismReconciliationWhenWorldRootBaselineChunkIsMissing(@TempDir Path tempDir)
            throws Throwable {
        QuickRollbackService service = new QuickRollbackService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        ProjectVersion root = version("v0001", VersionKind.WORLD_ROOT);
        List<PreparedChunkBatch> batches = List.of(new PreparedChunkBatch(new ChunkPoint(0, 0), List.of()));
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(-64, 64, -48)),
                List.of()
        );

        List<PreparedChunkBatch> reconciled = invokeWithMechanismReconciliation(
                service,
                layout,
                project,
                List.of(root),
                root,
                batches,
                scope
        );

        assertEquals(batches, reconciled);
    }

    @SuppressWarnings("unchecked")
    private static List<PreparedChunkBatch> invokeWithMechanismReconciliation(
            QuickRollbackService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion targetVersion,
            List<PreparedChunkBatch> batches,
            MechanismReplayScope scope
    ) throws Throwable {
        Method method = QuickRollbackService.class.getDeclaredMethod(
                "withMechanismReconciliation",
                ServerLevel.class,
                ProjectLayout.class,
                BuildProject.class,
                List.class,
                ProjectVersion.class,
                List.class,
                MechanismReplayScope.class,
                Bounds3i.class
        );
        method.setAccessible(true);
        try {
            return (List<PreparedChunkBatch>) method.invoke(
                    service,
                    null,
                    layout,
                    project,
                    versions,
                    targetVersion,
                    batches,
                    scope,
                    null
            );
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static ProjectVersion version(String id, VersionKind versionKind) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                "",
                "",
                List.of(),
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }
}
