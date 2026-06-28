package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.WorkZoneService;
import io.github.luma.storage.ProjectLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveWorkZoneTouchRecorderTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void entityTouchesExpandActiveZoneAtOldAndNewPositions() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        BuildProject project = BuildProject.createWorldWorkspace("Castle", "minecraft:overworld", NOW);
        new WorkZoneService().createZone(layout, project.id().toString(), "Displays", "builder", NOW);

        try (WorldMutationContext.SourceFrame ignored =
                     WorldMutationContext.pushSource(WorldMutationSource.PLAYER, "builder", "action-1", true)) {
            new ActiveWorkZoneTouchRecorder().record(
                    new TrackedProject(layout, project, List.of()),
                    List.of(new BlockPos(1, 64, 1), new BlockPos(32, 70, 48)),
                    NOW.plusSeconds(1)
            );
        }

        assertEquals(
                List.of(new WorkZoneCell(0, 4, 0), new WorkZoneCell(2, 4, 3)),
                new WorkZoneService().load(layout).zones().getFirst().cells()
        );
    }
}
