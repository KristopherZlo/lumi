package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestorePlanBuilderTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @TempDir
    Path tempDir;

    private final RestorePlanBuilder builder = new RestorePlanBuilder();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();

    @Test
    void buildsSnapshotAnchoredPatchPlanForBoundedProject() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshot(new ChunkPoint(0, 0)));
        this.patchMetaRepository.save(layout, patchMetadata("patch-0002", "v0002", new ChunkPoint(2, 0)));
        ProjectVersion anchor = version("v0001", "", "snapshot-0001", List.of());
        ProjectVersion target = version("v0002", "v0001", "", List.of("patch-0002"));

        RestorePlan plan = this.builder.build(layout, project(), List.of(anchor, target), target);

        assertEquals(anchor, plan.anchor());
        assertEquals(List.of("patch-0002"), plan.patchChain().stream().map(PatchMetadata::id).toList());
        assertTrue(plan.baselineGaps().isEmpty());
    }

    private static BuildProject project() {
        return BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(64, 128, 64)),
                new BlockPoint(0, 64, 0),
                NOW
        );
    }

    private static ProjectVersion version(String id, String parentVersionId, String snapshotId, List<String> patchIds) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentVersionId,
                snapshotId,
                patchIds,
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static PatchMetadata patchMetadata(String patchId, String versionId, ChunkPoint chunk) {
        return new PatchMetadata(
                patchId,
                "project",
                versionId,
                patchId + ".bin.lz4",
                List.of(new PatchChunkSlice(
                        chunk.x(),
                        chunk.z(),
                        1,
                        0L,
                        0,
                        List.of(),
                        0
                )),
                new PatchStats(1, 1)
        );
    }

    private static SnapshotData snapshot(ChunkPoint chunk) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:air");
        return new SnapshotData(
                "project",
                NOW,
                0,
                127,
                List.of(new SnapshotChunkData(
                        chunk.x(),
                        chunk.z(),
                        List.of(new SnapshotSectionData(4, List.of(state), new short[4096])),
                        java.util.Map.of(),
                        List.of()
                ))
        );
    }
}
