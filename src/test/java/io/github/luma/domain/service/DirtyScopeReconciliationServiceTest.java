package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirtyScopeReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void reconstructsEveryDirtySectionPositionAgainstHead() throws Exception {
        BuildProject project = project();
        ProjectVersion head = head(project);
        DirtyScopeReconciliationService service = new DirtyScopeReconciliationService(
                (layout, ignoredProject, versions, target, positions) -> states(positions, "minecraft:stone")
        );

        var draft = service.reconcileBlocks(
                new ProjectLayout(this.tempDir),
                project,
                List.of(head),
                head,
                dirty(project, head),
                List.of(liveChunk("minecraft:gold_block")),
                null,
                "Lumi safety ledger",
                NOW
        );

        assertEquals(4096, draft.changes().size());
        assertEquals("minecraft:stone", draft.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", draft.changes().getFirst().newValue().blockId());
    }

    @Test
    void refusesToPublishWhenDirtyLiveChunkIsUnavailable() {
        BuildProject project = project();
        ProjectVersion head = head(project);
        DirtyScopeReconciliationService service = new DirtyScopeReconciliationService(
                (layout, ignoredProject, versions, target, positions) -> Map.of()
        );

        assertThrows(IOException.class, () -> service.reconcileBlocks(
                new ProjectLayout(this.tempDir), project, List.of(head), head, dirty(project, head),
                List.of(), null, "Lumi safety ledger", NOW
        ));
    }

    private static ProjectDirtyScope dirty(BuildProject project, ProjectVersion head) {
        ProjectDirtyScope scope = ProjectDirtyScope.empty(project.id().toString(), "main", head.id());
        scope.markBlockSection(new ChunkSectionPoint(0, 0, 4));
        return scope;
    }

    private static ChunkSnapshotPayload liveChunk(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new ChunkSnapshotPayload(
                0, 0, 0, 255,
                List.of(new ChunkSectionSnapshotPayload(4, List.of(state), new long[0], 0)),
                Map.of()
        );
    }

    private static Map<BlockPoint, StatePayload> states(List<BlockPoint> positions, String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        LinkedHashMap<BlockPoint, StatePayload> result = new LinkedHashMap<>();
        positions.forEach(position -> result.put(position, new StatePayload(state, null)));
        return result;
    }

    private static BuildProject project() {
        return BuildProject.create(
                "project", "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(15, 255, 15)),
                new BlockPoint(0, 64, 0), NOW
        );
    }

    private static ProjectVersion head(BuildProject project) {
        return new ProjectVersion(
                "head", project.id().toString(), "main", "", "snapshot", "", List.of(),
                VersionKind.INITIAL, "author", "head", ChangeStats.empty(), PreviewInfo.none(),
                ExternalSourceInfo.manual(), NOW
        );
    }
}
