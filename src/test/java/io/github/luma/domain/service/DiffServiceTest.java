package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.VersionDiff;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

    private final DiffService diffService = new DiffService();

    @Test
    void extractBlockIdReadsStateName() {
        String blockId = this.diffService.extractBlockId("{Name:\"minecraft:stone\",Properties:{axis:\"y\"}}");

        assertEquals("minecraft:stone", blockId);
    }

    @Test
    void extractBlockIdFallsBackForBlankAndUnknown() {
        assertEquals("minecraft:air", this.diffService.extractBlockId(""));
        assertEquals("minecraft:unknown", this.diffService.extractBlockId("{foo:\"bar\"}"));
    }

    @Test
    void statesEqualUsesStructuredPayloads() {
        StatePayload left = payload("minecraft:stone");
        StatePayload right = payload("minecraft:stone");
        StatePayload changed = payload("minecraft:dirt");

        assertTrue(this.diffService.statesEqual(left, right));
        assertFalse(this.diffService.statesEqual(left, changed));
    }

    @Test
    void classifyStateChangeUsesBlockIdsWithoutSnbtRoundTrip() {
        StatePayload air = payload("minecraft:air");
        StatePayload stone = payload("minecraft:stone");
        StatePayload dirt = payload("minecraft:dirt");

        assertEquals(ChangeType.ADDED, this.diffService.classifyStateChange(air, stone));
        assertEquals(ChangeType.REMOVED, this.diffService.classifyStateChange(stone, air));
        assertEquals(ChangeType.CHANGED, this.diffService.classifyStateChange(stone, dirt));
    }

    @Test
    void applyDraftMergesEntityDiffsIntoCurrentStateDiff() {
        String entityId = "00000000-0000-0000-0000-000000000001";
        VersionDiff baseDiff = new VersionDiff(
                "v0001",
                "v0002",
                List.of(),
                1,
                List.of(entityChange(entityId, 1.0D, 2.0D))
        );

        VersionDiff currentDiff = this.diffService.applyDraft(
                baseDiff,
                List.of(),
                List.of(entityChange(entityId, 2.0D, 3.0D))
        );

        assertEquals("current", currentDiff.rightVersionId());
        assertEquals(1, currentDiff.changedEntityCount());
        assertEquals(1.0D, x(currentDiff.changedEntities().getFirst().oldValue()));
        assertEquals(3.0D, x(currentDiff.changedEntities().getFirst().newValue()));
    }

    @Test
    void compareVersionsSkipsEqualIndexedPatchSectionsWithoutReadingFrames(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("skip.mbp"));
        Instant now = Instant.parse("2026-04-20T10:00:00Z");
        PatchDataRepository patchDataRepository = new PatchDataRepository();
        PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
        VersionRepository versionRepository = new VersionRepository();
        StoredBlockChange change = new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload("minecraft:stone"),
                payload("minecraft:gold_block")
        );

        var leftMetadata = patchDataRepository.writePayload(
                layout,
                "patch-left",
                "project",
                "v0002",
                List.of(change)
        );
        var rightMetadata = patchDataRepository.writePayload(
                layout,
                "patch-right",
                "project",
                "v0003",
                List.of(change)
        );
        patchMetaRepository.save(layout, leftMetadata);
        patchMetaRepository.save(layout, rightMetadata);
        Files.write(layout.patchDataFile("patch-right"), new byte[] {1, 2, 3});

        versionRepository.save(layout, version("v0001", "", List.of(), VersionKind.WORLD_ROOT, now));
        versionRepository.save(layout, version("v0002", "v0001", List.of("patch-left"), VersionKind.MANUAL, now));
        versionRepository.save(layout, version("v0003", "v0001", List.of("patch-right"), VersionKind.MANUAL, now));

        VersionDiff diff = this.diffService.compareVersions(layout, "v0002", "v0003");

        assertEquals(0, diff.changedBlockCount());
        assertEquals(0, diff.changedEntityCount());
    }

    private static StatePayload payload(String blockId) {
        CompoundTag stateTag = new CompoundTag();
        stateTag.putString("Name", blockId);
        return new StatePayload(stateTag, null);
    }

    private static StoredEntityChange entityChange(String entityId, double oldX, double newX) {
        return new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity(entityId, oldX),
                entity(entityId, newX)
        );
    }

    private static ProjectVersion version(
            String id,
            String parentId,
            List<String> patchIds,
            VersionKind kind,
            Instant now
    ) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                "",
                patchIds,
                kind,
                "tester",
                "Save",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        );
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }

    private static double x(EntityPayload payload) {
        return payload.entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D);
    }
}
