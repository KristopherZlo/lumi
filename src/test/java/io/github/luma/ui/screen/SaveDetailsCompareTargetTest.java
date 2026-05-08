package io.github.luma.ui.screen;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.ui.controller.CompareScreenController;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaveDetailsCompareTargetTest {

    private static final Instant NOW = Instant.parse("2026-05-08T08:00:00Z");

    @Test
    void cleanActiveHeadComparesParentToSelectedSave() {
        ProjectVersion root = version("v0001", "");
        ProjectVersion head = version("v0002", root.id());

        SaveDetailsCompareTarget.Target target = SaveDetailsCompareTarget.seeChangesTarget(
                project("main"),
                List.of(head, root),
                List.of(ProjectVariant.main(head.id(), NOW)),
                head,
                null
        );

        assertEquals(root.id(), target.leftReference());
        assertEquals(head.id(), target.rightReference());
        assertEquals(head.id(), target.contextVersionId());
    }

    @Test
    void dirtyActiveHeadStillComparesSelectedSaveToCurrentBuild() {
        ProjectVersion root = version("v0001", "");
        ProjectVersion head = version("v0002", root.id());

        SaveDetailsCompareTarget.Target target = SaveDetailsCompareTarget.seeChangesTarget(
                project("main"),
                List.of(head, root),
                List.of(ProjectVariant.main(head.id(), NOW)),
                head,
                dirtyDraft()
        );

        assertEquals(head.id(), target.leftReference());
        assertEquals(CompareScreenController.CURRENT_WORLD_REFERENCE, target.rightReference());
    }

    @Test
    void inactiveSaveComparesSelectedSaveToCurrentBuild() {
        ProjectVersion root = version("v0001", "");
        ProjectVersion head = version("v0002", root.id());

        SaveDetailsCompareTarget.Target target = SaveDetailsCompareTarget.seeChangesTarget(
                project("feature"),
                List.of(head, root),
                List.of(ProjectVariant.main(head.id(), NOW)),
                head,
                null
        );

        assertEquals(head.id(), target.leftReference());
        assertEquals(CompareScreenController.CURRENT_WORLD_REFERENCE, target.rightReference());
    }

    private static BuildProject project(String activeVariantId) {
        return BuildProject.createWorldWorkspace("build", "minecraft:overworld", NOW)
                .withActiveVariantId(activeVariantId, NOW);
    }

    private static ProjectVersion version(String id, String parentId) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                "",
                List.of(),
                VersionKind.MANUAL,
                "Alex",
                id,
                ChangeStats.empty(),
                null,
                null,
                NOW
        );
    }

    private static RecoveryDraft dirtyDraft() {
        return new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        new StatePayload(state("minecraft:stone"), null),
                        new StatePayload(state("minecraft:glass"), null)
                ))
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
