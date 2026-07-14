package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialRestoreOperationPreparerTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private final PartialRestoreOperationPreparer preparer = new PartialRestoreOperationPreparer(null);

    @Test
    void zoneHardScopeSelectsOnlyMatchingDirtySection() {
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        ProjectDirtyScope scope = ProjectDirtyScope.empty(project.id().toString(), "main", "head");
        ChunkSectionPoint selected = new ChunkSectionPoint(0, 0, 4);
        ChunkSectionPoint outsideZone = new ChunkSectionPoint(0, 0, 5);
        scope.markBlockSections(List.of(selected, outsideZone));
        PartialRestoreRequest request = new PartialRestoreRequest(
                "project",
                "target",
                new io.github.luma.domain.model.Bounds3i(
                        new BlockPoint(0, 64, 0), new BlockPoint(15, 95, 15)
                ),
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.LUMI_REGION,
                "tester",
                Map.of()
        );

        ProjectDirtyScope isolated = this.preparer.selectDirtyScope(
                scope, project, request, point -> point.y() < 80
        );

        assertEquals(List.of(selected), isolated.blockSections().stream().toList());
    }

    @Test
    void verifiedPartialApplyAddsChangedSectionsToLedger() {
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "head");
        BlockPoint point = new BlockPoint(1, 70, 1);
        RecoveryDraft appliedDraft = new RecoveryDraft(
                "project", "main", "head", "tester", WorldMutationSource.RESTORE,
                NOW, NOW,
                List.of(new StoredBlockChange(point, StatePayload.air(), state("minecraft:stone")))
        );

        ProjectDirtyScope applied = this.preparer.appliedDirtyScope(scope, appliedDraft);

        assertEquals(List.of(ChunkSectionPoint.from(point)), applied.blockSections().stream().toList());
    }

    private static StatePayload state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
